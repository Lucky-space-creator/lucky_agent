package com.lucky.agent.core.util.verify;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 客观验证责任链（流程图 D / I 节点）。
 *
 * <p>执行顺序与聚合策略：</p>
 * <ol>
 *   <li><b>D 客观验证</b>：对计划中声明了 {@code verify} 的步骤，依次跑客观验证器
 *       （文件存在性 / 内容断言 / 校验命令退出码 / 状态码 / 测试结果），得到客观证据；</li>
 *   <li><b>I 达成度校验</b>：把客观证据连同执行日志交给 {@link LlmJudgeVerifier} 做结构化判定；</li>
 *   <li><b>客观优先</b>：任一客观验证未通过，即便模型自述「已完成」也判定为未达成，
 *       并把未通过项写入下一轮的 {@code continueGoal}，形成「验证失败 → 修复 → 再验证」闭环。</li>
 * </ol>
 *
 * <p>未声明 {@code verify} 的计划跳过客观验证，直接走主观判定（行为与改造前一致，不引入回归）。</p>
 */
@Slf4j
public class VerificationChain {

    private final List<ObjectiveVerifier> objectiveVerifiers;
    private final LlmJudgeVerifier llmJudge;
    private final CoreProperties properties;

    public VerificationChain(List<ObjectiveVerifier> objectiveVerifiers,
                             LlmJudgeVerifier llmJudge,
                             CoreProperties properties) {
        this.objectiveVerifiers = objectiveVerifiers == null ? List.of() : List.copyOf(objectiveVerifiers);
        this.llmJudge = llmJudge;
        this.properties = properties;
    }

    /**
     * 执行一次「客观验证 + 达成度判定」。
     *
     * @param plan      本轮计划（可为 null，表示未拆分任务）
     * @param ctx       会话上下文
     * @param goal      当前目标
     * @param execLog   本轮执行结果日志
     * @param publisher 事件发布器
     * @return 聚合结论
     */
    public VerificationVerdict verify(Plan plan, ConversationCtx ctx, String goal,
                                      String execLog, AgentEventPublisher publisher) {
        List<VerificationResult> evidence = new ArrayList<>();
        if (properties != null && properties.verificationEnabled() && plan != null
                && plan.steps() != null && !plan.steps().isEmpty()) {
            evidence.addAll(runObjective(plan, ctx, execLog, publisher));
        }

        VerificationVerdict verdict = llmJudge.judge(ctx, goal, execLog, evidence, publisher);

        // 客观优先：客观信号未通过时覆盖主观「已达成」结论
        List<VerificationResult> failures = verdict.objectiveFailures();
        if (!failures.isEmpty() && verdict.done()) {
            log.info("客观验证未通过但模型判定已达成，以客观信号为准：session={} failures={}",
                    ctx.sessionId(), failures.size());
            publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(),
                    "【验证】客观校验未通过（" + failures.size() + " 项），以客观结果为准，继续修复。"));
            StringBuilder sb = new StringBuilder(verdict.continueGoal());
            sb.append("\n【客观验证未通过项（必须修复后才能判定达成）】");
            failures.forEach(f -> sb.append("\n- ").append(f.detail()));
            return new VerificationVerdict(false, verdict.summary(), sb.toString(), verdict.evidence());
        }
        return verdict;
    }

    /** D: 逐步骤跑客观验证器。 */
    private List<VerificationResult> runObjective(Plan plan, ConversationCtx ctx, String execLog,
                                                  AgentEventPublisher publisher) {
        List<VerificationResult> results = new ArrayList<>();
        String sessionId = ctx.sessionId();
        boolean any = false;
        for (Plan.PlanStep step : plan.steps()) {
            if (step == null || step.verify() == null) {
                continue;
            }
            for (ObjectiveVerifier verifier : objectiveVerifiers) {
                if (!verifier.supports(step, ctx)) {
                    continue;
                }
                any = true;
                VerificationResult result;
                try {
                    result = verifier.verify(step, ctx, execLog);
                } catch (Exception e) {
                    log.warn("客观验证器异常：verifier={}", verifier.type(), e);
                    result = VerificationResult.failed(verifier.type(), "验证器异常：" + e.getMessage());
                }
                results.add(result);
                publisher.publish(sessionId, AgentEvent.thought(sessionId,
                        "【验证】" + verifier.type() + " 校验"
                                + (result.skipped() ? "跳过" : (result.passed() ? "通过" : "未通过"))
                                + "：" + result.detail()));
            }
        }
        if (!any) {
            publisher.publish(sessionId, AgentEvent.progress(sessionId,
                    "【验证】本轮计划未声明客观校验项，改用模型整体判定。"));
        }
        return results;
    }
}
