package com.lucky.agent.workflow.adapter;

/**
 * 代码/命令节点沙箱适配器（集成缝隙）。
 * <p>对应设计中的「进程/资源/文件系统沙箱」。默认 {@link LocalSandboxAdapter} 以 ProcessBuilder
 * 受限执行 + 超时；<b>默认关闭，需显式启用</b>（与工程内 {@code CmdSandbox} 的策略一致）。
 * 后续可替换为 agent-executor 的 realpath 边界 + OS 沙箱实现。</p>
 */
public interface SandboxAdapter {

    /**
     * 在受限环境中执行命令。
     *
     * @param command   命令
     * @param timeoutMs 超时（毫秒，&lt;=0 用默认）
     * @return 执行结果
     */
    CommandResult run(String command, long timeoutMs);

    /** 沙箱是否启用。 */
    default boolean enabled() {
        return true;
    }
}
