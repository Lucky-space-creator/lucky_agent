package com.lucky.agent.workflow.adapter;

/**
 * 命令执行结果（沙箱）。
 *
 * @param exitCode   退出码（-1 表示未执行/被拦截）
 * @param stdout     标准输出
 * @param stderr     标准错误
 * @param timedOut   是否超时
 * @param durationMs 耗时（毫秒）
 */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs) {

    public boolean success() {
        return exitCode == 0 && !timedOut;
    }

    public static CommandResult blocked(String reason) {
        return new CommandResult(-1, "", reason, false, 0);
    }
}
