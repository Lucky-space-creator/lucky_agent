package core.support.verify;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.verify.CommandVerifier;
import com.lucky.agent.core.util.verify.FileVerifier;
import com.lucky.agent.core.util.verify.LlmJudgeVerifier;
import com.lucky.agent.core.util.verify.VerificationChain;
import com.lucky.agent.core.util.verify.VerificationResult;
import com.lucky.agent.core.util.verify.VerificationVerdict;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客观证据不得在判定环节被丢弃（台账 #14）。
 *
 * <p>此前 {@code LlmJudgeVerifier.judgeOnce} 只把一条 {@code judged(...)} 放进 verdict，
 * 传入的客观 {@code evidence} 被整包丢弃。后果是连锁的：</p>
 * <ul>
 *   <li>{@code VerificationVerdict.objectiveFailures()} 恒空 → 「客观优先」成死分支，
 *       模型说完成就算完成，哪怕文件根本没生成；</li>
 *   <li>{@code Orchestrator.evidenceConfidence} 与 {@code ChainVerifierAdapter} 看不到客观项
 *       → 置信度恒 0.5 → 早停阈值 0.3 永不触发。</li>
 * </ul>
 *
 * <p>注意与 {@code VerificationChainTest} 的分工：那边 mock 掉 judge 并原样回传 evidence，
 * 测的是「链的覆盖逻辑」；这边用真实 judge，测的是「judge 有没有把证据吃掉」——正是之前漏掉的一环。</p>
 */
class LlmJudgeVerifierEvidenceTest {

    private static final String SESSION = "s-1";

    private ConversationCtx ctx() {
        return ConversationCtx.builder()
                .sessionRef(new SessionRef(SESSION, "u-1", "ws-1"))
                .phase(Phase.ACT)
                .permissionLevel(PermissionLevel.FULL)
                .goal("生成配置文件")
                .extra(Map.of("modelId", "m-1"))
                .build();
    }

    /** 模型固定输出「已完成」的结构化结论。 */
    private LlmJudgeVerifier judgeSayingDone() {
        ChatModel model = mock(ChatModel.class);
        AiMessage ai = AiMessage.from("已按要求生成配置。\n{\"done\":true,\"missing\":[],\"summary\":\"已完成\"}");
        ChatResponse resp = ChatResponse.builder().aiMessage(ai).build();
        org.mockito.Mockito.doReturn(resp).when(model).chat(any(ChatRequest.class));

        ModelRouter router = mock(ModelRouter.class);
        when(router.resolve("m-1")).thenReturn(model);
        return new LlmJudgeVerifier(router);
    }

    @Test
    void judgeMustKeepObjectiveEvidence() {
        List<VerificationResult> evidence = List.of(
                VerificationResult.failed("file", "文件不存在：config.yml"),
                VerificationResult.passed("command", "校验命令通过：mvn -q test"));

        VerificationVerdict verdict = judgeSayingDone().judge(
                ctx(), "生成配置文件", "子任务日志", evidence, mock(AgentEventPublisher.class));

        assertTrue(verdict.done(), "模型判定已完成");
        assertEquals(1, verdict.objectiveFailures().size(), "客观未通过项必须带回 verdict");
        assertTrue(verdict.hasObjectiveSignal(), "存在客观项时不得退化为纯主观");
        assertTrue(verdict.evidenceText().contains("config.yml"), "证据文本应含客观细节");
    }

    @Test
    void objectiveFailureOverridesModelDoneThroughChain() {
        // 端到端：模型说完成 + 文件根本没生成 → 整条链必须判未达成
        FileService files = mock(FileService.class);
        when(files.stat(anyString(), anyString()))
                .thenReturn(Mono.just(ExecResult.failure(FileOp.OpType.STAT, "r1", "文件不存在")));
        when(files.read(anyString(), anyString()))
                .thenReturn(Mono.just(ExecResult.failure(FileOp.OpType.READ, "r2", "文件不存在")));
        when(files.exec(anyString(), anyString()))
                .thenReturn(Mono.just(ExecResult.success(FileOp.OpType.EXEC, "r3")));

        CoreProperties props = new CoreProperties(12, 30, 30, -1, false, 4, 300, 3, 2,
                "reactor", true, 120, 500, 0.3);
        VerificationChain chain = new VerificationChain(
                List.of(new FileVerifier(files, 30), new CommandVerifier(files, 30)),
                judgeSayingDone(), props);

        Plan plan = new Plan("生成配置", List.of(
                new Plan.PlanStep(1, "file", "写出 config.yml", "config.yml", true,
                        new Plan.VerifySpec("file", null, "config.yml", null))), true);

        VerificationVerdict verdict = chain.verify(plan, ctx(), "生成配置文件", "子任务日志",
                mock(AgentEventPublisher.class));

        assertFalse(verdict.done(), "客观验证未通过时，模型自述完成必须被覆盖");
        assertEquals(1, verdict.objectiveFailures().size());
        assertTrue(verdict.continueGoal().contains("客观验证未通过项"), verdict.continueGoal());
    }
}
