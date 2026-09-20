package com.lucky.agent.core.runtime.contract;

/**
 * 统一执行指标：token / 耗时 / 重试 / 工具调用次数，随结果返回，用于预算与可观测性。
 */
public record ExecutionMetrics(long tokenUsed, long durationMs, int retries, int toolCalls) {

    public static ExecutionMetrics empty() {
        return new ExecutionMetrics(0L, 0L, 0, 0);
    }

    public static ExecutionMetrics ofTokens(long tokens) {
        return new ExecutionMetrics(tokens, 0L, 0, 0);
    }

    public ExecutionMetrics plus(ExecutionMetrics other) {
        if (other == null) {
            return this;
        }
        return new ExecutionMetrics(
                tokenUsed + other.tokenUsed,
                durationMs + other.durationMs,
                retries + other.retries,
                toolCalls + other.toolCalls);
    }
}
