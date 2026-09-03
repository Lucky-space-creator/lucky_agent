package com.lucky.agent.executor.arm;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行沙箱（默认关闭，开启需用户显式授权）。
 *
 * <p>MVP 以 ProcessBuilder 受限执行（工作目录 = 工作空间根，带超时）；OS 级沙箱在 Phase 2 补齐。</p>
 */
public class CmdSandbox {

    private final boolean enabled;
    private final long timeoutSec;

    public CmdSandbox(boolean enabled, long timeoutSec) {
        this.enabled = enabled;
        this.timeoutSec = timeoutSec;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 在工作空间根内执行命令。
     *
     * @param op           文件操作（EXEC）
     * @param workspaceRoot 工作空间根
     * @return 执行结果（仅元数据）
     */
    public ExecResult run(FileOp op, Path workspaceRoot) {
        if (!enabled) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行沙箱未启用，需用户显式授权");
        }
        String command = op.args() == null ? null : (String) op.args().get("command");
        if (command == null || command.isBlank()) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "缺少命令参数 command");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(parseCommand(command));
            pb.directory(workspaceRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行超时（" + timeoutSec + "s）");
            }
            int exit = process.exitValue();
            return ExecResult.success(FileOp.OpType.EXEC, op.requestId())
                    .summary("exit=" + exit).content(output);
        } catch (IOException e) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令启动失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行被中断");
        }
    }

    private List<String> parseCommand(String command) {
        List<String> parts = new ArrayList<>();
        for (String part : command.split("\\s+")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }
}
