package com.lucky.agent.cli;

import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.RunResult;

/**
 * 一轮运行的产出（供 REPL 收尾、headless 输出与退出码判定共用）。
 *
 * @param result     内核返回结果（超时/异常时为 {@code null}）
 * @param capture    本轮事件采集（渲染之外的收尾取值来源）
 * @param failure    订阅或提交链路上的异常（无则 {@code null}）
 * @param timedOut   是否因超出单轮等待上限而放弃等待
 * @param durationMs 本轮耗时（毫秒）
 */
public record TurnOutcome(RunResult result, TurnCapture capture, Throwable failure,
                          boolean timedOut, long durationMs) {

    /** 内核显式报错（{@link RunResult#error()} 非空）。 */
    public boolean failed() {
        return failure != null || (result != null && result.error() != null && !result.error().isBlank());
    }

    /** 错误文本（无则 {@code null}）。 */
    public String errorText() {
        if (failure != null) {
            String msg = failure.getMessage();
            return msg == null || msg.isBlank() ? failure.getClass().getSimpleName() : msg;
        }
        return result == null ? null : result.error();
    }

    /**
     * 映射为退出码（见 {@link CliExitCode}）。
     *
     * <p>判定优先级：异常/错误 &gt; 预算类终止 &gt; 挂起未决 &gt; 成功。
     * 「预算耗尽」由内核终态 reason（{@code max_turns} / {@code max_budget} / {@code budget_exhausted}）识别。</p>
     */
    public int exitCode(boolean askDenied) {
        if (askDenied) {
            return CliExitCode.PERMISSION_DENIED;
        }
        if (timedOut) {
            return CliExitCode.FAILURE;
        }
        if (failed()) {
            return CliExitCode.FAILURE;
        }
        String reason = capture == null ? null : capture.stopReason();
        if ("max_turns".equals(reason) || "max_budget".equals(reason) || "budget_exhausted".equals(reason)) {
            return CliExitCode.BUDGET_EXHAUSTED;
        }
        if (result == null) {
            return CliExitCode.PENDING_UNRESOLVED;
        }
        if (result.status() == RunResult.RunStatus.MAX_TURNS || result.status() == RunResult.RunStatus.MAX_BUDGET) {
            return CliExitCode.BUDGET_EXHAUSTED;
        }
        return CliExitCode.SUCCESS;
    }
}
