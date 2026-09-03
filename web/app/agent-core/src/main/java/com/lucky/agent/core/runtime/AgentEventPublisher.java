package com.lucky.agent.core.runtime;

import com.lucky.agent.common.dto.AgentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AgentEvent 事件发布器：为每个会话维护一条事件流，Web/CLI 消费同一 Flux。
 *
 * <p><b>为什么用 multicast 而非 replay</b>：会话是长期复用的（多轮对话共用一个 sessionId）。
 * 若用 {@code replay().latest()}，下一轮对话建立 SSE 订阅时会立刻收到上一轮缓存的
 * {@code stop}/{@code error}，前端据此把新一轮的消息误判为「已结束」，表现为助手消息空白。
 * 改为 multicast + 缓冲后，订阅者只收到订阅之后产生的事件，跨轮互不污染；
 * 无订阅者时事件先入缓冲，POST 先于订阅到达也不会丢首帧。</p>
 *
 * <p>{@link #complete(String)} 会同时移除 sink：运行结束后本轮事件流即销毁，
 * 下一轮订阅时自动重建，不留悬挂连接。</p>
 */
@Component
public class AgentEventPublisher {

    private final ConcurrentMap<String, Sinks.Many<AgentEvent>> sinks = new ConcurrentHashMap<>();

    /** 订阅会话事件流（只接收订阅之后产生的事件）。 */
    public Flux<AgentEvent> stream(String sessionId) {
        return sink(sessionId).asFlux();
    }

    /** 发布一条事件。 */
    public void publish(String sessionId, AgentEvent event) {
        sink(sessionId).tryEmitNext(event);
    }

    /**
     * 本轮运行结束：完成并销毁该会话的事件流。
     * 移除而非仅 complete，避免已完成（永不发射）的 sink 被下一轮订阅复用。
     */
    public void complete(String sessionId) {
        Sinks.Many<AgentEvent> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
        }
    }

    /** 清理会话事件流（会话销毁时调用）。 */
    public void remove(String sessionId) {
        Sinks.Many<AgentEvent> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
        }
    }

    private Sinks.Many<AgentEvent> sink(String sessionId) {
        // 无订阅者时先缓冲，保证 SSE 订阅晚于事件产生也能收到完整首帧
        return sinks.computeIfAbsent(sessionId, k -> Sinks.many().multicast().onBackpressureBuffer());
    }
}
