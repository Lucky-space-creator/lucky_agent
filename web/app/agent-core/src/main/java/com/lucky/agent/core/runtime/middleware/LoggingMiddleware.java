package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志/可观测中间件：把循环各阶段以进度事件推送到前端，并写结构化日志。
 *
 * <p>位于中间件链最内层（{@link MiddlewareOrder#LOGGING}），因此重试中间件的每一次尝试
 * 都会产生独立日志与进度事件；同时累计工具使用次数（属性 {@link #ATTR_TOOL_USAGE}），
 * 供压缩中间件写入结构化字段。</p>
 */
public class LoggingMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(LoggingMiddleware.class);

    /** 工具使用次数属性键（Map&lt;String, Integer&gt;）。 */
    public static final String ATTR_TOOL_USAGE = "toolUsage";

    @Override
    public int order() {
        return MiddlewareOrder.LOGGING;
    }

    @Override
    public void beforeLlm(MiddlewareContext ctx) {
        log.debug("[trace={}] 调用 LLM（第 {} 轮）", traceId(ctx), ctx.runtime().attribute("iteration"));
        publish(ctx, "【循环】调用 LLM…");
    }

    @Override
    public void beforeTool(MiddlewareContext ctx) {
        ToolCall call = ctx.toolCall();
        if (call != null) {
            log.debug("[trace={}] 执行工具 {}", traceId(ctx), call.name());
            publish(ctx, "【循环】执行工具: " + call.name());
        }
    }

    @Override
    public void afterTool(MiddlewareContext ctx) {
        if (ctx.result() != null) {
            log.debug("[trace={}] 工具结果状态={} 耗时={}ms", traceId(ctx), ctx.result().status(),
                    ctx.result().metrics().durationMs());
        }
        ToolCall call = ctx.toolCall();
        if (call != null) {
            countToolUsage(ctx.runtime(), call.name());
        }
    }

    /** 累计工具使用次数，写入运行时属性供压缩中间件携带。 */
    private void countToolUsage(RuntimeContext rc, String toolName) {
        Map<String, AtomicInteger> counters = counters(rc);
        counters.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        counters.forEach((k, v) -> snapshot.put(k, v.get()));
        rc.attribute(ATTR_TOOL_USAGE, snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> counters(RuntimeContext rc) {
        Object existing = rc.attribute(ATTR_TOOL_USAGE + "#counters");
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, AtomicInteger>) map;
        }
        Map<String, AtomicInteger> created = new ConcurrentHashMap<>();
        rc.attribute(ATTR_TOOL_USAGE + "#counters", created);
        Object raced = rc.attribute(ATTR_TOOL_USAGE + "#counters");
        return (raced instanceof Map<?, ?>) ? (Map<String, AtomicInteger>) raced : created;
    }

    private String traceId(MiddlewareContext ctx) {
        return ctx.runtime().trace() == null ? null : ctx.runtime().trace().traceId();
    }

    private void publish(MiddlewareContext ctx, String message) {
        AgentEventPublisher publisher = ctx.runtime().publisher();
        String sessionId = ctx.runtime().sessionId();
        if (publisher != null && sessionId != null) {
            publisher.publish(sessionId, AgentEvent.progress(sessionId, message));
        }
    }
}
