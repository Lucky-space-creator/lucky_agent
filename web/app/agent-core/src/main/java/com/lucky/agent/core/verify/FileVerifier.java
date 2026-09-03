package com.lucky.agent.core.verify;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.executor.api.FileService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 文件客观验证器：以「文件是否存在 / 内容是否包含断言文本」作为客观信号。
 *
 * <p>适用于「新建文件」「修改配置」「生成产物」类步骤——只要产物落盘并满足内容断言，
 * 即认为该步骤客观达成，不再依赖模型自述。</p>
 *
 * <p>读取走 {@link FileService}，仍受权限级别与执行臂硬边界约束。</p>
 */
@Slf4j
public class FileVerifier implements ObjectiveVerifier {

    private final FileService fileService;
    private final long timeoutSec;

    public FileVerifier(FileService fileService, long timeoutSec) {
        this.fileService = fileService;
        this.timeoutSec = timeoutSec <= 0 ? 30 : timeoutSec;
    }

    @Override
    public String type() {
        return "file";
    }

    @Override
    public boolean supports(Plan.PlanStep step, ConversationCtx ctx) {
        if (step == null || step.verify() == null) {
            return false;
        }
        String path = pathOf(step);
        return path != null && !path.isBlank();
    }

    @Override
    public VerificationResult verify(Plan.PlanStep step, ConversationCtx ctx, String execLog) {
        String path = pathOf(step);
        String ws = ctx == null ? null : ctx.workspaceId();
        if (path == null || path.isBlank()) {
            return VerificationResult.skipped(type(), "未声明待校验路径");
        }
        try {
            ExecResult stat = fileService.stat(ws, path).block(Duration.ofSeconds(timeoutSec));
            if (stat == null || !stat.ok()) {
                return VerificationResult.failed(type(),
                        "文件不存在或不可访问：" + path + (stat == null ? "" : "（" + stat.error() + "）"));
            }
            String contains = step.verify().contains();
            if (contains == null || contains.isBlank()) {
                return VerificationResult.passed(type(), "文件已生成：" + path);
            }
            ExecResult read = fileService.read(ws, path).block(Duration.ofSeconds(timeoutSec));
            String content = read == null ? null : read.content();
            if (content != null && content.contains(contains)) {
                return VerificationResult.passed(type(), "文件存在且包含断言内容：" + path);
            }
            return VerificationResult.failed(type(), "文件存在但未包含断言内容「" + contains + "」：" + path);
        } catch (Exception e) {
            log.warn("文件验证异常：path={}", path, e);
            return VerificationResult.failed(type(), "文件验证异常：" + e.getMessage());
        }
    }

    private String pathOf(Plan.PlanStep step) {
        Plan.VerifySpec verify = step.verify();
        String path = verify == null ? null : verify.path();
        return path == null || path.isBlank() ? step.target() : path;
    }
}
