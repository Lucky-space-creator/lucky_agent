package com.lucky.agent.core.runtime.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 外部命令验证器（客观）：执行校验命令（测试 / lint / 类型检查等），退出码 0 视为通过。
 * <p>仅做只读校验语义，命令由调用方在 {@link VerificationRequest#checkCommands()} 显式给出；
 * 实际接入时应叠加执行臂权限与 realpath 边界（复用 agent-executor）。</p>
 */
public class ExternalCommandVerifier implements Verifier {

    private static final Logger log = LoggerFactory.getLogger(ExternalCommandVerifier.class);

    private final long timeoutMs;
    private final String workingDir;

    public ExternalCommandVerifier(long timeoutMs, String workingDir) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000L;
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "external-command";
    }

    @Override
    public boolean objective() {
        return true;
    }

    @Override
    public VerificationOutcome verify(VerificationRequest request) {
        if (request.checkCommands().isEmpty()) {
            return null; // 无客观校验项：交由链上下一个验证器（返回 null 表示不表态）
        }
        List<String> evidence = new ArrayList<>();
        boolean allPassed = true;
        for (String command : request.checkCommands()) {
            int exit = run(command);
            boolean passed = exit == 0;
            allPassed &= passed;
            evidence.add(command + " -> exit=" + exit + (passed ? " (pass)" : " (fail)"));
            if (!passed) {
                break;
            }
        }
        return allPassed
                ? VerificationOutcome.done("全部客观校验通过", evidence)
                : new VerificationOutcome(false, 1.0, true, evidence,
                        "客观校验未通过：" + evidence, request.goal());
    }

    private int run(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> shell = windows ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
        ProcessBuilder pb = new ProcessBuilder(shell);
        if (workingDir != null && !workingDir.isBlank()) {
            pb.directory(new java.io.File(workingDir));
        }
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("校验命令超时: {}", command);
                return -1;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!out.isBlank()) {
                log.debug("校验命令 [{}] 输出: {}", command, out.length() > 500 ? out.substring(0, 500) : out);
            }
            return process.exitValue();
        } catch (IOException e) {
            log.warn("校验命令执行失败: {} ({})", command, e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
