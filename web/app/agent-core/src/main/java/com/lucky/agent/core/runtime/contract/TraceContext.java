package com.lucky.agent.core.runtime.contract;

import java.util.UUID;

/**
 * 追踪上下文：traceId 串联一次任务的全部 span，spanId 标识当前步骤/子代理/工具调用。
 * <p>支持按 traceId 回放（配合每轮状态快照与结构化 JSON 日志）。</p>
 */
public record TraceContext(String traceId, String spanId, String parentSpanId) {

    public static TraceContext root() {
        String trace = UUID.randomUUID().toString();
        return new TraceContext(trace, shortId(), null);
    }

    /** 派生一个子 span（同 traceId，父 span 指向当前）。 */
    public TraceContext child(String explicitSpanId) {
        return new TraceContext(traceId, explicitSpanId == null ? shortId() : explicitSpanId, spanId);
    }

    public TraceContext child() {
        return child(null);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
