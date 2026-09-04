package com.lucky.agent.core.verify;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客观验证责任链测试（流程图 D / I 节点）。
 *
 * <p>核心验收点：客观信号优先于模型主观结论——客观验证未通过时，
 * 即便模型自述「已完成」，也必须判定为未达成并携带未通过项回到 B 节点。</p>
 */
class VerificationChainTest {

    private static final String SESSION = "s-1";

    private ConversationCtx ctx() {
        return ConversationCtx.builder()
                .sessionRef(new SessionRef(SESSION, "u-1", "ws-1"))
                .phase(Phase.ACT)
                .permissionLevel(PermissionLevel.FULL)
                .goal("生成配置文件")
                .build();
    }

    private FileService fileService(boolean statOk, String content) {
        FileService fileService = mock(FileService.class);
        when(fileService.stat(anyString(), anyString()))
                .thenReturn(Mono.just(statOk
                        ? ExecResult.success(FileOp.OpType.STAT, "r1")
                        : ExecResult.failure(FileOp.OpType.STAT, "r1", "文件不存在")));
        when(fileService.read(anyString(), anyString()))
                .thenReturn(Mono.just(new ExecResult().ok(true).opType(FileOp.OpType.READ).content(content)));
        when(fileService.exec(anyString(), anyString()))
                .thenReturn(Mono.just(new ExecResult().ok(true).opType(FileOp.OpType.EXEC).summary(content)));
        return fileService;
    }

    private VerificationChain chain(FileService fileService, LlmJudgeVerifier judge, boolean enabled) {
        FileVerifier fileVerifier = new FileVerifier(fileService, 30);
        CommandVerifier commandVerifier = new CommandVerifier(fileService, 30);
        CoreProperties properties = new CoreProperties(12, 30, 30, -1, false, 4, 300, 3, 2, "reactor", enabled, 120, 500, 0.3);
        return new VerificationChain(List.of(fileVerifier, commandVerifier), judge, properties);
    }

    @Test
    void testObjectiveFailed_OverridesSubjectiveDone() {
        LlmJudgeVerifier judge = mock(LlmJudgeVerifier.class);
        when(judge.judge(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new VerificationVerdict(true, "已完成", "目标", (List<VerificationResult>) inv.getArgument(3)));

        Plan plan = new Plan("生成配置", List.of(
                new Plan.PlanStep(1, "file", "写出 config.yml", "config.yml", true,
                        new Plan.VerifySpec("file", null, "config.yml", null))), true);

        VerificationVerdict verdict = chain(fileService(false, ""), judge, true)
                .verify(plan, ctx(), "生成配置", "子任务日志", mock(AgentEventPublisher.class));

        assertFalse(verdict.done(), "客观验证未通过时，模型自述已达成应被覆盖为未达成");
        assertTrue(verdict.continueGoal().contains("客观验证未通过项"), "未达成时应把客观未通过项带回下一轮");
        assertEquals(1, verdict.objectiveFailures().size());
    }

    @Test
    void testObjectivePassed_KeepsSubjectiveDone() {
        LlmJudgeVerifier judge = mock(LlmJudgeVerifier.class);
        when(judge.judge(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new VerificationVerdict(true, "全部完成", "目标", (List<VerificationResult>) inv.getArgument(3)));

        Plan plan = new Plan("生成配置", List.of(
                new Plan.PlanStep(1, "file", "写出 config.yml", "config.yml", true,
                        new Plan.VerifySpec("file", null, "config.yml", "port"))), true);

        VerificationVerdict verdict = chain(fileService(true, "port: 8080"), judge, true)
                .verify(plan, ctx(), "生成配置", "子任务日志", mock(AgentEventPublisher.class));

        assertTrue(verdict.done());
        assertEquals("全部完成", verdict.summary());
        assertTrue(verdict.hasObjectiveSignal());
    }

    @Test
    void testCommandVerifier_UsesExitCodeAsSignal() {
        FileService fileService = fileService(true, "");
        when(fileService.exec(anyString(), eq("mvn -q test")))
                .thenReturn(Mono.just(ExecResult.failure(FileOp.OpType.EXEC, "r2", "exit code 1")));
        when(fileService.exec(anyString(), eq("curl -s localhost/health")))
                .thenReturn(Mono.just(new ExecResult().ok(true).opType(FileOp.OpType.EXEC).summary("200")));

        Plan.PlanStep failedStep = new Plan.PlanStep(1, "shell", "跑测试", null, true,
                new Plan.VerifySpec("command", "mvn -q test", null, null));
        Plan.PlanStep okStep = new Plan.PlanStep(2, "shell", "探活", null, true,
                new Plan.VerifySpec("command", "curl -s localhost/health", null, "200"));

        LlmJudgeVerifier judge = mock(LlmJudgeVerifier.class);
        when(judge.judge(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new VerificationVerdict(true, "完成", "目标", (List<VerificationResult>) inv.getArgument(3)));

        Plan plan = new Plan("验证服务", List.of(failedStep, okStep), true);
        VerificationVerdict verdict = chain(fileService, judge, true)
                .verify(plan, ctx(), "验证服务", "日志", mock(AgentEventPublisher.class));

        assertFalse(verdict.done(), "校验命令退出码非 0 应判定未达成");
        assertEquals(1, verdict.objectiveFailures().size(), "仅失败的校验命令计入未通过项");
    }

    @Test
    void testVerificationDisabled_SkipsObjectiveVerifiers() {
        LlmJudgeVerifier judge = mock(LlmJudgeVerifier.class);
        when(judge.judge(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new VerificationVerdict(true, "完成", "目标", List.of()));

        Plan plan = new Plan("生成配置", List.of(
                new Plan.PlanStep(1, "file", "写出 config.yml", "config.yml", true,
                        new Plan.VerifySpec("file", null, "config.yml", null))), true);

        VerificationVerdict verdict = chain(fileService(false, ""), judge, false)
                .verify(plan, ctx(), "生成配置", "日志", mock(AgentEventPublisher.class));

        assertTrue(verdict.done(), "关闭客观验证后应完全交由模型判定");
        assertFalse(verdict.hasObjectiveSignal());
    }

    @Test
    void testNoVerifySpec_NoObjectiveSignal() {
        LlmJudgeVerifier judge = mock(LlmJudgeVerifier.class);
        when(judge.judge(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new VerificationVerdict(false, "仍缺一项", "目标\n【需补齐事项】\n- 补文档", List.of()));

        Plan plan = new Plan("生成配置", List.of(
                new Plan.PlanStep(1, "tool", "整理结论", null, true)), true);

        VerificationVerdict verdict = chain(fileService(true, ""), judge, true)
                .verify(plan, ctx(), "生成配置", "日志", mock(AgentEventPublisher.class));

        assertFalse(verdict.done());
        assertFalse(verdict.hasObjectiveSignal(), "未声明 verify 的步骤不产生客观信号");
        assertTrue(verdict.continueGoal().contains("需补齐事项"));
    }
}
