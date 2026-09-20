package com.lucky.agent.workflow.adapter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地命令沙箱实现：ProcessBuilder 受限执行 + 超时熔断。
 * <p><b>默认关闭</b>，需在配置中显式开启（{@code lucky.workflow.sandbox.enabled=true}）。
 * 后续可替换为基于 realpath 边界 + OS 配额（CPU/内存）的强沙箱。</p>
 */
public class LocalSandboxAdapter implements SandboxAdapter {

    private final boolean enabled;
    private final long defaultTimeoutMs;
    private final String workingDir;

    public LocalSandboxAdapter(boolean enabled, long defaultTimeoutMs, String workingDir) {
        this.enabled = enabled;
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : 10_000L;
        this.workingDir = workingDir;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public CommandResult run(String command, long timeoutMs) {
        if (!enabled) {
            return CommandResult.blocked("命令沙箱未启用（需显式授权 lucky.workflow.sandbox.enabled=true）");
        }
        if (command == null || command.isBlank()) {
            return CommandResult.blocked("命令为空");
        }
        long timeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> shell = windows ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);

        ProcessBuilder pb = new ProcessBuilder(shell);
        if (workingDir != null && !workingDir.isBlank()) {
            pb.directory(new File(workingDir));
        }
        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, read(process.getInputStream()), read(process.getErrorStream()),
                        true, System.currentTimeMillis() - start);
            }
            return new CommandResult(process.exitValue(), read(process.getInputStream()),
                    read(process.getErrorStream()), false, System.currentTimeMillis() - start);
        } catch (IOException e) {
            return CommandResult.blocked("命令执行 IO 异常: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.blocked("命令执行被中断");
        }
    }

    private String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
