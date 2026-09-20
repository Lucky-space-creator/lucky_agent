package com.lucky.agent.core.util.runtime;

import com.lucky.agent.common.dto.AgentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * AgentEvent 事件发布器：为每个会话维护一条事件流，Web/CLI 消费同一 Flux。
 *
 * <p><b>为什么不用 replay 缓存</b>：会话是长期复用的（多轮对话共用一个 sessionId）。
 * 若用 {@code replay()} 缓存全部历史事件，下一轮对话建立 SSE 订阅时会立刻收到上一轮缓存的
 * {@code stop}/{@code error}，前端据此把新一轮的消息误判为「已结束」，表现为助手消息空白。
 * 故采用「multicast sink + 无订阅者期间暂存（pending）」，订阅者只收到订阅之后产生的事件，跨轮互不污染。</p>
 *
 * <p><b>事件投递保证（关键）</b>：multicast sink 在没有订阅者时 {@code tryEmitNext} 会返回
 * {@code FAIL_ZERO_SUBSCRIBER} 并丢弃事件。若任务完成早于前端 SSE 订阅建立，{@code stop}/{@code error}
 * 会被丢弃，前端随后订阅到一个空流，永远收不到收尾事件，表现为「任务已完成但界面仍显示正在输出」。
 * 因此发布时若无订阅者，事件先入 {@link #pending} 暂存；订阅建立时（{@link #stream(String)} 的 defer +
 * {@code doOnSubscribe}，此时订阅者已注册）把暂存原子取出并补发到 sink，保证收尾事件不丢、顺序正确。</p>
 *
 * <p>{@link #complete(String)} 只完成并销毁 sink，不清暂存（若运行结束早于订阅，暂存需在订阅时补发）；
 * 跨轮残留由运行前调用 {@link #reset(String)} 清空（见 {@code ConversationManager}），避免把上一轮
 * 未消费的收尾事件回放到新一轮。</p>
 */
@Component
public class AgentEventPublisher {

    /** 暂存上限：无订阅者期间长任务产生的碎片事件防止无限堆积（超出丢弃最旧）。 */
    private static final int PENDING_LIMIT = 2000;

    /** 发射冲突（多线程同时 tryEmitNext 会 FAIL_NON_SERIALIZED）时的重试上限，避免事件被静默丢弃。 */
    private static final int EMIT_RETRY_LIMIT = 200;

    private final ConcurrentMap<String, Sinks.Many<AgentEvent>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Queue<AgentEvent>> pending = new ConcurrentHashMap<>();

    /** 订阅会话事件流：订阅建立（订阅者已注册）后立即补发暂存事件，再接续订阅后产生的实时事件。 */
    public Flux<AgentEvent> stream(String sessionId) {
        return Flux.defer(() -> {
            Sinks.Many<AgentEvent> s = sink(sessionId);
            // doOnSubscribe 在 sink 注册订阅者之后触发：此刻补发暂存可被缓冲/投递，且顺序 = 先暂存后实时
            return s.asFlux().doOnSubscribe(ignored -> drainInto(sessionId, s));
        });
    }

    /** 发布一条事件：有订阅者直接发射；无订阅者先入暂存（订阅时补发，不丢收尾事件）。 */
    public void publish(String sessionId, AgentEvent event) {
        Sinks.Many<AgentEvent> s = sinks.get(sessionId);
        if (s != null && s.currentSubscriberCount() > 0) {
            // 与 drainInto 并发时 tryEmitNext 可能 FAIL_NON_SERIALIZED：自旋重试，避免事件被静默丢弃
            for (int i = 0; i < EMIT_RETRY_LIMIT; i++) {
                Sinks.EmitResult r = s.tryEmitNext(event);
                if (r.isSuccess()) {
                    return;
                }
                if (r == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                    Thread.onSpinWait();
                    continue;
                }
                break; // FAIL_ZERO_SUBSCRIBER / FAIL_OVERFLOW / FAIL_CANCELLED → 入暂存待下次订阅补发
            }
        }
        // 无订阅者，或发射始终失败（订阅者刚取消/连接断开）：入暂存
        Queue<AgentEvent> q = pending.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());
        if (q.size() >= PENDING_LIMIT) {
            q.poll();
        }
        q.add(event);
    }

    /** 新一轮运行开始前清空暂存，避免把上一轮（无订阅者时的）收尾事件回放到新一轮。 */
    public void reset(String sessionId) {
        Queue<AgentEvent> q = pending.remove(sessionId);
        if (q != null) {
            q.clear();
        }
    }

    /**
     * 本轮运行结束：完成并销毁该会话的事件流。
     * 只完成/移除 sink，不清暂存——若运行结束早于订阅，暂存需在随后的订阅时补发。
     */
    public void complete(String sessionId) {
        Sinks.Many<AgentEvent> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
        }
    }

    /** 清理会话事件流（会话销毁时调用）。 */
    public void remove(String sessionId) {
        reset(sessionId);
        Sinks.Many<AgentEvent> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
        }
    }

    private Sinks.Many<AgentEvent> sink(String sessionId) {
        return sinks.computeIfAbsent(sessionId, k -> Sinks.many().multicast().onBackpressureBuffer());
    }

    /** 把当前暂存事件原子取出并转发到已注册订阅者的 sink（订阅建立时补发）。 */
    private void drainInto(String sessionId, Sinks.Many<AgentEvent> s) {
        Queue<AgentEvent> q = pending.get(sessionId);
        if (q == null) {
            return;
        }
        AgentEvent e;
        while ((e = q.poll()) != null) {
            s.tryEmitNext(e);
        }
    }
}
