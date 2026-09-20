package com.lucky.agent.core.runtime.middleware;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测中间件：每轮写入结构化 JSON 状态快照，并按 traceId 缓冲，支持事后回放。
 *
 * <p>快照字段：traceId / spanId / 轮次 / 阶段 / 结果状态 / 预算用量 / 时间戳。
 * 日志为单行 JSON（便于 Logback JSON encoder 与采集器直接消费）；内存中保留最近 N 条，
 * 供 {@code /trace/{traceId}} 之类的回放接口读取。</p>
 */
public class StateSnapshotMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(StateSnapshotMiddleware.class);

    /** 快照缓冲属性键。 */
    public static final String ATTR_SNAPSHOTS = "traceSnapshots";

    /** 默认保留条数。 */
    private static final int DEFAULT_CAPACITY = 64;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int capacity;

    public StateSnapshotMiddleware() {
        this(DEFAULT_CAPACITY);
    }

    public StateSnapshotMiddleware(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public int order() {
        return MiddlewareOrder.SNAPSHOT;
    }

    @Override
    public void beforeLlm(MiddlewareContext ctx) {
        snapshot(ctx, "before-llm");
    }

    @Override
    public void afterLlm(MiddlewareContext ctx) {
        snapshot(ctx, "after-llm");
    }

    @Override
    public void afterTool(MiddlewareContext ctx) {
        snapshot(ctx, "after-tool");
    }

    /** 读取某次运行的全部快照（供 traceId 回放）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> snapshots(RuntimeContext rc) {
        Object value = rc.attribute(ATTR_SNAPSHOTS);
        return (value instanceof List<?> list)
                ? Collections.unmodifiableList((List<Map<String, Object>>) list)
                : List.of();
    }

    private void snapshot(MiddlewareContext ctx, String stage) {
        RuntimeContext rc = ctx.runtime();
        TraceContext trace = rc.trace();
        ExecutionResult result = ctx.result();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ts", Instant.now().toString());
        snapshot.put("traceId", trace == null ? null : trace.traceId());
        snapshot.put("spanId", trace == null ? null : trace.spanId());
        snapshot.put("parentSpanId", trace == null ? null : trace.parentSpanId());
        snapshot.put("stage", stage);
        snapshot.put("iteration", rc.attribute("iteration"));
        snapshot.put("tool", ctx.toolCall() == null ? null : ctx.toolCall().name());
        snapshot.put("status", result == null ? null : String.valueOf(result.status()));
        snapshot.put("blocked", ctx.blocked());
        snapshot.put("budget", rc.budget() == null ? Map.of() : rc.budget().snapshot());

        record(rc, snapshot);

        try {
            log.info("[snapshot] {}", objectMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            log.warn("[snapshot] 序列化失败: {}", e.getMessage());
        }
    }

    private void record(RuntimeContext rc, Map<String, Object> snapshot) {
        List<Map<String, Object>> buffer = buffer(rc);
        synchronized (buffer) {
            buffer.add(snapshot);
            while (buffer.size() > capacity) {
                buffer.remove(0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buffer(RuntimeContext rc) {
        Object existing = rc.attribute(ATTR_SNAPSHOTS);
        if (existing instanceof List<?>) {
            return (List<Map<String, Object>>) existing;
        }
        rc.attribute(ATTR_SNAPSHOTS, Collections.synchronizedList(new ArrayList<Map<String, Object>>()));
        Object raced = rc.attribute(ATTR_SNAPSHOTS);
        return (raced instanceof List<?>) ? (List<Map<String, Object>>) raced : List.of();
    }
}
