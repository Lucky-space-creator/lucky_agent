package com.lucky.agent.core.metrics;

import com.lucky.agent.cache.toolresult.FingerprintCache;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.AgentEventType;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话指标收集器（P3 透明面板后端数据源）。
 *
 * <p>在会话提交时调用 {@link #start(String)} 订阅该会话的事件流，按事件类型聚合到
 * {@link SessionMetrics}；{@link #snapshot(String)} 供 Web 端点轮询读取。缓存命中/未命中
 * 取自 {@link FingerprintCache} 真实计数器（全局累计，诚实反映本机缓存收益）。会话结束或
 * 销毁时调用 {@link #stop(String)} 取消订阅并清理。收集器只读取事件、不修改业务状态。</p>
 */
@Component
public class MetricsCollector {

    private final AgentEventPublisher publisher;
    private final FingerprintCache fingerprintCache;
    private final Map<String, SessionMetrics> metrics = new ConcurrentHashMap<>();
    private final Map<String, Disposable> subs = new ConcurrentHashMap<>();

    public MetricsCollector(AgentEventPublisher publisher, FingerprintCache fingerprintCache) {
        this.publisher = publisher;
        this.fingerprintCache = fingerprintCache;
    }

    /**
     * 开始收集某会话指标（会话级累计，跨轮不重置）。
     *
     * <p>每轮重新订阅当前事件流：{@code AgentEventPublisher.complete} 会销毁并重建 sink，
     * 若只在首次 start 时订阅一次，第二轮起旧订阅已 complete、收不到任何事件（token 等
     * 指标停止增长）。故每次 start 先释放旧订阅再订阅最新 sink，指标在会话内持续累加。</p>
     */
    public void start(String sessionId) {
        SessionMetrics m = metrics.computeIfAbsent(sessionId, k -> new SessionMetrics());
        if (m.elapsedMs() == 0) {
            m.setStartAt(System.currentTimeMillis());
        }
        Disposable prev = subs.remove(sessionId);
        if (prev != null && !prev.isDisposed()) {
            prev.dispose();
        }
        subs.put(sessionId, subscribe(sessionId));
    }

    private Disposable subscribe(String sessionId) {
        Flux<AgentEvent> stream = publisher.stream(sessionId);
        return stream.doOnNext(event -> accumulate(sessionId, event)).subscribe();
    }

    /** 读取当前指标快照（无则空指标）。 */
    public SessionMetrics snapshot(String sessionId) {
        SessionMetrics m = metrics.computeIfAbsent(sessionId, k -> new SessionMetrics());
        // 真实缓存计数器（全局累计，诚实反映本机缓存收益）
        m.recordCache(fingerprintCache.hits(), fingerprintCache.misses());
        return m;
    }

    /** 停止收集并清理。 */
    public void stop(String sessionId) {
        Disposable d = subs.remove(sessionId);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        metrics.remove(sessionId);
    }

    /** 供 ReactEngine 直接上报模型调用次数（每次 model.chat 计 1）。 */
    public void reportModelCall(String sessionId) {
        snapshot(sessionId).incModelCalls();
    }

    private void accumulate(String sessionId, AgentEvent event) {
        SessionMetrics m = metrics.computeIfAbsent(sessionId, k -> new SessionMetrics());
        Map<String, Object> p = event.payload();
        switch (event.type()) {
            case "token" -> {
                // used 为本次模型调用增量，累加到会话级总量
                Object used = p.get(AgentEvent.KEY_USED);
                if (used instanceof Number n) {
                    m.addToken(n.longValue());
                }
                Object input = p.get("input");
                if (input instanceof Number in) {
                    m.addInput(in.longValue());
                }
                Object output = p.get("output");
                if (output instanceof Number out) {
                    m.addOutput(out.longValue());
                }
                Object model = p.get(AgentEvent.KEY_MODEL);
                if (model != null) {
                    m.setLastModel(String.valueOf(model));
                }
                Object total = p.get(AgentEvent.KEY_TOTAL);
                if (total instanceof Number t) {
                    m.setContextWindow(t.longValue());
                }
            }
            case "error" -> m.incErrors();
            case "action" -> {
                // 工具调用次数与成败统一在 tool_result 中裁决，ACTION 仅表示启动意图，不重复计数。
            }
            case "tool_result" -> {
                Object tool = p.get(AgentEvent.KEY_SOURCE);
                if (tool != null) {
                    boolean ok = Boolean.TRUE.equals(p.get(AgentEvent.KEY_OK));
                    m.recordTool(String.valueOf(tool), ok);
                }
            }
            case "skill_invoke" -> m.recordSkill();
            case "mcp_invoke" -> m.recordMcp();
            case "task_progress" -> {
                Object taskId = p.get(AgentEvent.KEY_TASK_ID);
                Object done = p.get(AgentEvent.KEY_DONE);
                Object total = p.get(AgentEvent.KEY_TOTAL);
                Object status = p.get(AgentEvent.KEY_STATUS);
                if (taskId != null) {
                    m.updateSubAgent(String.valueOf(taskId),
                            done instanceof Number d ? ((Number) d).intValue() : 0,
                            total instanceof Number t ? ((Number) t).intValue() : 0,
                            status == null ? "running" : String.valueOf(status));
                }
            }
            default -> { /* 其他事件不计入指标 */ }
        }
    }
}
