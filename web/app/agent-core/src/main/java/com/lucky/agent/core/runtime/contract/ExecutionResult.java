package com.lucky.agent.core.runtime.contract;

import java.util.List;
import java.util.Map;

/**
 * 统一执行结果契约：单 Agent、逐步骤、子代理、工具、验证等所有执行路径均返回本类型。
 *
 * @param status    状态
 * @param output    主输出文本（摘要/结论）
 * @param artifacts 产物
 * @param error     错误信息（成功时为空）
 * @param metrics   指标
 * @param trace     追踪上下文
 * @param metadata  附加元数据（置信度、验证维度、来源工具等）
 */
public record ExecutionResult(
        ExecutionStatus status,
        String output,
        List<Artifact> artifacts,
        String error,
        ExecutionMetrics metrics,
        TraceContext trace,
        Map<String, Object> metadata) {

    public ExecutionResult {
        artifacts = (artifacts == null) ? List.of() : List.copyOf(artifacts);
        metrics = (metrics == null) ? ExecutionMetrics.empty() : metrics;
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }

    public static ExecutionResult success(String output) {
        return new ExecutionResult(ExecutionStatus.SUCCESS, output, List.of(), null,
                ExecutionMetrics.empty(), null, Map.of());
    }

    public static ExecutionResult success(String output, List<Artifact> artifacts,
                                          ExecutionMetrics metrics, TraceContext trace) {
        return new ExecutionResult(ExecutionStatus.SUCCESS, output, artifacts, null, metrics, trace, Map.of());
    }

    public static ExecutionResult failure(String error) {
        return new ExecutionResult(ExecutionStatus.FAILED, null, List.of(), error,
                ExecutionMetrics.empty(), null, Map.of());
    }

    public static ExecutionResult ask(String reason) {
        return new ExecutionResult(ExecutionStatus.ASK, reason, List.of(), null,
                ExecutionMetrics.empty(), null, Map.of());
    }

    public static ExecutionResult cancelled() {
        return new ExecutionResult(ExecutionStatus.CANCELLED, null, List.of(), null,
                ExecutionMetrics.empty(), null, Map.of());
    }

    public static ExecutionResult budgetExhausted(String detail) {
        return new ExecutionResult(ExecutionStatus.BUDGET_EXHAUSTED, null, List.of(), detail,
                ExecutionMetrics.empty(), null, Map.of());
    }

    public static ExecutionResult timeout(String detail) {
        return new ExecutionResult(ExecutionStatus.TIMEOUT, null, List.of(), detail,
                ExecutionMetrics.empty(), null, Map.of());
    }

    /** 追加元数据（不可变，返回新实例）。 */
    public ExecutionResult withMeta(String key, Object value) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new ExecutionResult(status, output, artifacts, error, metrics, trace, merged);
    }
}
