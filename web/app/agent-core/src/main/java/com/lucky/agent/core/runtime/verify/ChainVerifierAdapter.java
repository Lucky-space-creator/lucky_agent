package com.lucky.agent.core.runtime.verify;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.verify.VerificationChain;
import com.lucky.agent.core.util.verify.VerificationResult;
import com.lucky.agent.core.util.verify.VerificationVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证链桥接器：把既有 {@link VerificationChain}（文件/命令客观验证器 + LLM 结构化判定）
 * 接入运行时 {@link Verifier} 契约，避免重复实现验证能力。
 *
 * <p>关键点：既有验证链以 {@link Plan.PlanStep} 为输入，而运行时以 {@link VerificationRequest} 为输入，
 * 本适配器据请求<b>合成一个校验计划</b>（{@code checkCommands} → 命令校验步骤，
 * {@code evidence.files} → 文件校验步骤），从而让既有的 {@code CommandVerifier}/{@code FileVerifier}
 * 真正跑起来，并继续受权限级别与执行臂硬边界约束。</p>
 *
 * <p>无客观校验项时合成计划为 null，验证链自动降级为 LLM 整体判定——
 * 与「客观优先、主观兜底」的约定一致。</p>
 *
 * <p>不表态（返回 {@code null}）的两种情形：缺少运行时上下文/会话引用，或验证链抛异常。
 * 这让 {@link CompositeVerifier} 能平稳降级到链上的轻量验证器。</p>
 */
public class ChainVerifierAdapter implements Verifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifierAdapter.class);

    /** 无订阅者时的事件发布器：既有验证链会发布进度事件，此处保证空上下文下不 NPE。 */
    private static final AgentEventPublisher NOOP_PUBLISHER = new AgentEventPublisher();

    /** 主观判定的置信度上限（无客观信号时）。 */
    private static final double SUBJECTIVE_CONFIDENCE = 0.5;

    private final VerificationChain chain;

    public ChainVerifierAdapter(VerificationChain chain) {
        this.chain = chain;
    }

    @Override
    public String name() {
        return "verification-chain";
    }

    @Override
    public boolean objective() {
        return true; // 链内含文件/命令客观验证器
    }

    @Override
    public VerificationOutcome verify(VerificationRequest request) {
        RuntimeContext rc = request.runtime();
        if (chain == null || rc == null || rc.ref() == null) {
            return null; // 不表态：交由轻量验证器
        }
        try {
            // 有真实计划则按其 verify 声明逐步骤校验；否则据请求合成校验计划
            Plan plan = request.plan() != null ? request.plan() : syntheticPlan(request);
            VerificationVerdict verdict = chain.verify(plan, ctxOf(rc, request),
                    request.goal(), request.output(), publisherOf(rc));
            boolean objective = verdict.hasObjectiveSignal();
            return new VerificationOutcome(
                    verdict.done(),
                    objective ? objectivePassRate(verdict) : SUBJECTIVE_CONFIDENCE,
                    objective,
                    verdict.evidence().stream().map(VerificationResult::detail).toList(),
                    verdict.summary(),
                    verdict.continueGoal());
        } catch (RuntimeException e) {
            log.warn("[verify] 验证链异常，降级到链上轻量验证器: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 客观验证通过率：作为置信度与早停判定的<b>单一事实源</b>。
     *
     * <p>此前 thin 主循环用「有客观证据即 1.0」，而 langgraph 主循环另算一遍通过率，
     * 同一份客观证据得出两个不同置信度——正是双主循环漂移的根源。现收敛到此处：
     * 全部通过 = 1.0，部分/全部未通过 = 实际通过率，无客观项 = 主观兜底值。</p>
     */
    private double objectivePassRate(VerificationVerdict verdict) {
        List<VerificationResult> evidence = verdict.evidence();
        if (evidence == null || evidence.isEmpty()) {
            return SUBJECTIVE_CONFIDENCE;
        }
        long objective = evidence.stream().filter(e -> e.objective() && !e.skipped()).count();
        if (objective == 0) {
            return SUBJECTIVE_CONFIDENCE;
        }
        long passed = evidence.stream().filter(e -> e.objective() && !e.skipped() && e.passed()).count();
        return (double) passed / objective;
    }

    /** 由验证请求合成校验计划：命令 → 命令校验步骤；产物文件 → 文件校验步骤。 */
    private Plan syntheticPlan(VerificationRequest request) {
        List<Plan.PlanStep> steps = new ArrayList<>();
        int id = 1;
        for (String command : request.checkCommands()) {
            if (command != null && !command.isBlank()) {
                steps.add(new Plan.PlanStep(id++, "verify", "客观校验命令", null, true,
                        new Plan.VerifySpec(Plan.VerifySpec.TYPE_COMMAND, command, null, null)));
            }
        }
        for (String path : filePaths(request)) {
            steps.add(new Plan.PlanStep(id++, "verify", "产物文件校验", path, true,
                    new Plan.VerifySpec(Plan.VerifySpec.TYPE_FILE, null, path, null)));
        }
        return steps.isEmpty() ? null : new Plan(request.goal(), steps, true);
    }

    private List<String> filePaths(VerificationRequest request) {
        List<String> paths = new ArrayList<>();
        Object files = request.evidence().get("files");
        if (files instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(s -> !s.isBlank()).forEach(paths::add);
        }
        return paths;
    }

    private ConversationCtx ctxOf(RuntimeContext rc, VerificationRequest request) {
        if (rc.conversation() != null) {
            return rc.conversation();
        }
        // 合成最小上下文：权限取工作区默认值（MODIFY），避免凭空提权让命令校验被误执行
        return ConversationCtx.builder()
                .sessionRef(rc.ref())
                .goal(request.goal())
                .permissionLevel(PermissionLevel.defaultValue())
                .build();
    }

    private AgentEventPublisher publisherOf(RuntimeContext rc) {
        return rc.publisher() == null ? NOOP_PUBLISHER : rc.publisher();
    }
}
