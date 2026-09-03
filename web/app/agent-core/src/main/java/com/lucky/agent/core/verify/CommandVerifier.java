package com.lucky.agent.core.verify;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.executor.api.FileService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 命令客观验证器：以「校验命令的退出码 / 执行是否成功」作为客观信号。
 *
 * <p>适用于流程图所述的三类客观校验：</p>
 * <ul>
 *   <li>构建/测试：{@code mvn -q test}、{@code npm run build} 等，靠退出码判定；</li>
 *   <li>服务状态码：{@code curl -s -o /dev/null -w '%{http_code}' ...} 配合 {@code contains} 断言期望值；</li>
 *   <li>数据库校验：{@code psql -c '...'}、{@code mysql -e '...'} 等，靠退出码 + 输出断言。</li>
 * </ul>
 *
 * <p>仅在权限级别为 {@link PermissionLevel#FULL} 时执行；其余级别直接跳过（跳过不计入失败），
 * 交由主观判定兜底，避免因权限不足把「无法验证」误判成「验证失败」。</p>
 */
@Slf4j
public class CommandVerifier implements ObjectiveVerifier {

    private final FileService fileService;
    private final long timeoutSec;

    public CommandVerifier(FileService fileService, long timeoutSec) {
        this.fileService = fileService;
        this.timeoutSec = timeoutSec <= 0 ? 120 : timeoutSec;
    }

    @Override
    public String type() {
        return "command";
    }

    @Override
    public boolean supports(Plan.PlanStep step, ConversationCtx ctx) {
        if (step == null || step.verify() == null) {
            return false;
        }
        String command = step.verify().command();
        return command != null && !command.isBlank();
    }

    @Override
    public VerificationResult verify(Plan.PlanStep step, ConversationCtx ctx, String execLog) {
        String command = step.verify().command();
        if (ctx == null || ctx.permissionLevel() != PermissionLevel.FULL) {
            return VerificationResult.skipped(type(),
                    "当前权限级别不允许执行校验命令（需 FULL）：" + command);
        }
        try {
            ExecResult result = fileService.exec(ctx.workspaceId(), command)
                    .block(Duration.ofSeconds(timeoutSec));
            if (result == null || !result.ok()) {
                return VerificationResult.failed(type(),
                        "校验命令未通过：" + command + "（" + (result == null ? "无返回" : result.error()) + "）");
            }
            String expect = step.verify().contains();
            String output = result.content() == null
                    ? (result.summary() == null ? "" : result.summary())
                    : result.content();
            if (expect != null && !expect.isBlank() && !output.contains(expect)) {
                return VerificationResult.failed(type(),
                        "校验命令通过但输出未包含期望内容「" + expect + "」：" + command);
            }
            return VerificationResult.passed(type(), "校验命令通过：" + command);
        } catch (Exception e) {
            log.warn("命令验证异常：command={}", command, e);
            return VerificationResult.failed(type(), "校验命令执行异常：" + e.getMessage());
        }
    }
}
