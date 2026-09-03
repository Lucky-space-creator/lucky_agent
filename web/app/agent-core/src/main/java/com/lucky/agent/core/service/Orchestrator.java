package com.lucky.agent.core.service;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.engine.EarlyStopPolicy;
import com.lucky.agent.core.engine.StepLimitGuard;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.PlanValidator;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.planactask.ActScheduler;
import com.lucky.agent.core.planactask.AskSuspender;
import com.lucky.agent.core.planactask.PlanGenerator;
import com.lucky.agent.core.planactask.Replanner;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.core.runtime.RunBudget;
import com.lucky.agent.core.subagent.TaskProgressTracker;
import com.lucky.agent.core.verify.VerificationChain;
import com.lucky.agent.core.verify.VerificationResult;
import com.lucky.agent.core.verify.VerificationVerdict;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编排器（Orchestrator）：按「分析 → 拆分 → 逐任务执行 → 客观验证 → 达成度判定 →
 * 记忆管理 → 再分析」的主回环驱动 REACT 引擎（对应 AGENTS.md §4.5 / D10 多 Agent 编排）。
 *
 * <p>语义对照（与核心执行链路流程图一致）：</p>
 * <pre>
 *   输入 → B: LLM 分析需求（PLAN，由模型判断是否需拆子任务）
 *        → C: 是否拆小任务？
 *             ├ 否（plan 为空/无步骤）→ 单次 REACT 直接执行（轻量验证后输出）
 *             └ 是 → E: 拆小任务 → F: 逐任务执行
 *                      ├ 失败 → G: 异常捕获与局部重试（指数退避，重试前回滚失败消息）
 *                      └ 重试超限 → H: 提前终止并告知用户
 *        → D: 客观验证器校验（文件存在性 / 校验命令退出码 / 测试 / 状态码 / 数据库）
 *        → I: 达成度校验（客观证据注入模型做结构化判定，客观优先覆盖主观结论）
 *             ├ 通过 → N 安全阀 → P 总结 → Q 输出
 *             └ 未通过 → J: 是否达到用户目标？
 *                          ├ 已达到 → N → P → Q
 *                          └ 未达到(且未超上限) → K: 记忆管理（上下文压缩 + 长期记忆沉淀）→ 回 B
 * </pre>
 *
 * <p>关键约束（按已确认决策接线）：</p>
 * <ul>
 *   <li><b>P0-1 ASK</b>：执行中遇到高危确认（status=ask），若权限级别非 FULL（即未选「完全权限+自动执行」）
 *       则中断整个编排、向用户说明无法执行的原因并持久化挂起（D7）；FULL 下执行臂已自动放行，不会进入此分支。</li>
 *   <li><b>P1-2 独立预算</b>：每次 {@code run} 新建独立 {@link RunBudget}，不跨多轮 submit 累积。</li>
 *   <li><b>P2-1 模型判定拆分</b>：是否拆子任务由 PLAN 阶段模型产出决定，不在代码层做强关键词分流。</li>
 *   <li><b>P2-2 轻量验证</b>：无需拆分的单步任务也走 {@link VerificationChain}（无客观声明时仅主观判定）。</li>
 *   <li><b>P2-3 重试回滚</b>：子任务重试前回滚该次尝试追加的消息，避免失败噪音污染上下文。</li>
 * </ul>
 */
@Component
public class Orchestrator {

    private final Engine engine;
    private final ConversationStateManager stateManager;
    private final PlanGenerator planGenerator;
    private final PlanValidator planValidator;
    private final Replanner replanner;
    private final TaskProgressTracker taskProgressTracker;
    private final VerificationChain verificationChain;
    private final LoopMemoryManager loopMemoryManager;
    private final CoreProperties properties;
    private final AskSuspender askSuspender;
    private final StepLimitGuard stepLimitGuard;
    private final EarlyStopPolicy earlyStopPolicy;
    private final ActScheduler actScheduler;

    public Orchestrator(Engine engine, ConversationStateManager stateManager,
                        PlanGenerator planGenerator, PlanValidator planValidator, Replanner replanner,
                        TaskProgressTracker coreTaskProgressTracker,
                        VerificationChain verificationChain,
                        LoopMemoryManager loopMemoryManager,
                        CoreProperties properties,
                        AskSuspender askSuspender,
                        StepLimitGuard stepLimitGuard,
                        EarlyStopPolicy earlyStopPolicy,
                        ActScheduler actScheduler) {
        this.engine = engine;
        this.stateManager = stateManager;
        this.planGenerator = planGenerator;
        this.planValidator = planValidator;
        this.replanner = replanner;
        this.taskProgressTracker = coreTaskProgressTracker;
        this.verificationChain = verificationChain;
        this.loopMemoryManager = loopMemoryManager;
        this.properties = properties;
        this.askSuspender = askSuspender;
        this.stepLimitGuard = stepLimitGuard;
        this.earlyStopPolicy = earlyStopPolicy;
        this.actScheduler = actScheduler;
    }

    /**
     * 运行一次编排（复杂/简单任务统一入口）。
     *
     * @param ref       会话
     * @param ctx       会话上下文（goal 为原始目标）
     * @param publisher 事件发布器
     * @return 最终执行结果（总结文本）
     */
    public EngineRunResult run(SessionRef ref, ConversationCtx ctx, AgentEventPublisher publisher) {
        String sessionId = ref.sessionId();
        String goal = ctx.goal();
        int maxIter = properties.orchestratorMaxIterations();
        StringBuilder accumulated = new StringBuilder();
        // P1-2：每次编排独立预算，不跨多轮 submit 累积（会话可无限叠加，靠压缩+新窗口）
        RunBudget budget = new RunBudget(properties.runMaxTurns(), properties.runMaxBudget());

        for (int iter = 1; ; iter++) {
            // N 安全阀（循环头统一检查）：迭代次数 / 回合数 / token 预算 任一耗尽 → 强制结束并总结
            Optional<EngineRunResult> tripped = checkSafetyValve(
                    sessionId, iter, maxIter, budget, accumulated, publisher);
            if (tripped.isPresent()) {
                return tripped.get();
            }

            // B: LLM 分析需求（PLAN 阶段产出结构化计划，由模型判断是否需拆子任务）
            publisher.publish(sessionId, AgentEvent.thought(sessionId,
                    "【分析】正在分析需求并判断是否需拆分为子任务（第 " + iter + " 轮）…"));
            Plan plan = analyze(ctx, goal, publisher);
            budget.incrementTurn();

            if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
                // C=否：模型判定无需拆分 → 单次 REACT 直接执行（P2-1，由模型判断）
                publisher.publish(sessionId, AgentEvent.thought(sessionId,
                        "【分析】模型判定无需拆分，直接执行（单次 REACT）。"));
                // ACT 阶段调度器（P1-1 接入）：以目标驱动 ACT 运行
                EngineRunResult direct = actScheduler.execute(ctx, new Plan(goal, List.of(), false)).block();
                if (direct != null) {
                    budget.addTokens(direct.tokenUsed());
                }
                if (direct == null) {
                    return EngineRunResult.error(sessionId, Phase.ACT, "引擎无返回结果");
                }
                if (direct.status() != null && direct.status().equals("ask")) {
                    // P0-1：非 FULL 权限下高危操作需确认，中断编排并说明原因
                    String reason = askReason(ctx);
                    askSuspender.suspend(ref, reason, ctx.permissionLevel() == null
                            ? "unknown" : ctx.permissionLevel().getCode());
                    return direct;
                }
                if (direct.error() != null) {
                    return direct;
                }
                // P2-2：单步任务也走轻量验证（无客观声明时仅主观判定）
                VerificationVerdict v = verificationChain.verify(null, ctx, goal,
                        direct.finalText(), publisher);
                budget.incrementTurn();
                if (v.done()) {
                    return EngineRunResult.of(sessionId, Phase.ACT, v.summary(),
                            direct.tokenUsed(), direct.model(), "success");
                }
                publisher.publish(sessionId, AgentEvent.thought(sessionId,
                        "【复查】单步执行未完全达成，进入下一轮分析。"));
                loopMemoryManager.manage(ctx, stateManager.session(ref),
                        direct.finalText() + v.evidenceText(), publisher);
                goal = v.continueGoal();
                continue;
            }

            // C=是：拆分小任务
            publisher.publish(sessionId, AgentEvent.thought(sessionId,
                    "【分析】拆分为 " + plan.steps().size() + " 个子任务，逐一执行。"));
            List<AgentEvent.TaskItem> tasks = plan.steps().stream()
                    .map(s -> new AgentEvent.TaskItem("s" + s.id(), s.desc()))
                    .toList();
            taskProgressTracker.plan(sessionId, tasks, publisher);

            // E→F: 逐任务执行（G: 局部重试；H: 重试超限提前终止）
            ExecOutcome out = executeAll(ctx, stateManager.session(ref), tasks,
                    publisher, properties.orchestratorMaxRetries());
            budget.addTokens(out.tokens());
            if (out.ask()) {
                // P0-1：高危操作需确认，已挂起，中断整个编排返回
                return out.askResult();
            }
            if (out.aborted()) {
                accumulated.append(out.log());
                return EngineRunResult.of(sessionId, Phase.ACT,
                        "部分子任务多次重试仍失败，已提前终止并告知。"
                                + "已完成/失败的执行情况：\n" + accumulated,
                        0, null, "aborted");
            }
            accumulated.append(out.log());

            // D → I: 客观验证 + 达成度判定（客观证据注入模型，客观信号优先于主观结论）
            VerificationVerdict verdict = verificationChain.verify(
                    plan, ctx, goal, out.log().toString(), publisher);
            budget.incrementTurn();

            // J: 已达用户目标 → N 安全阀已在循环头通过 → P 总结 → Q 输出
            if (verdict.done()) {
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        out.tokens(), null, "success");
            }

            // 低置信早停（EarlyStopPolicy）：客观验证通过率过低，转 ASK 让用户决策后续方向
            double confidence = evidenceConfidence(verdict);
            if (earlyStopPolicy.shouldStop(confidence)) {
                String reason = "多次执行后仍无法客观验证达成，需你明确指示后续方向。";
                askSuspender.suspend(ref, reason, "low_confidence");
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        out.tokens(), null, "ask");
            }

            // J=未达成且未超上限 → K: 记忆管理（上下文压缩 + 长期记忆沉淀）→ 回 B 再分析
            publisher.publish(sessionId, AgentEvent.thought(sessionId,
                    "【复查】仍有未完成项，进行记忆/上下文管理后进入下一轮分析（第 " + iter + " 轮已完成）。"));
            loopMemoryManager.manage(ctx, stateManager.session(ref),
                    out.log() + verdict.evidenceText(), publisher);
            goal = verdict.continueGoal();
        }
    }

    /** 客观验证通过率：作为 EarlyStopPolicy 的置信输入（无客观证据时视为中性 0.5）。 */
    private double evidenceConfidence(VerificationVerdict verdict) {
        List<VerificationResult> evidence = verdict.evidence();
        if (evidence == null || evidence.isEmpty()) {
            return 0.5;
        }
        long objective = evidence.stream().filter(VerificationResult::objective).count();
        if (objective == 0) {
            return 0.5;
        }
        long passed = evidence.stream().filter(r -> r.objective() && r.passed() && !r.skipped()).count();
        return (double) passed / objective;
    }

    /**
     * N 安全阀：迭代次数 / 回合数 / token 预算三重检查。
     *
     * @return 触发时返回兜底结果（含已完成的进度总结），未触发返回空
     */
    private Optional<EngineRunResult> checkSafetyValve(String sessionId, int iter, int maxIter,
                                                       RunBudget budget, StringBuilder accumulated,
                                                       AgentEventPublisher publisher) {
        String reason = null;
        String detail = null;
        if (iter > maxIter) {
            reason = "max_iterations";
            detail = "已达最大迭代次数（" + maxIter + "）";
        } else if (budget.turnsExhausted()) {
            reason = "max_turns";
            detail = "已达最大回合数（" + budget.maxTurns() + "）";
        } else if (budget.budgetExhausted()) {
            reason = "max_budget";
            detail = "已耗尽 token 预算（" + budget.usedTokens() + " tokens）";
        }
        if (reason == null) {
            return Optional.empty();
        }
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【安全阀】" + detail + "，强制结束并总结当前进度。"));
        String summary = "任务执行" + detail + "，未能完全达成目标。已完成的子任务与结果如下：\n" + accumulated;
        return Optional.of(EngineRunResult.of(sessionId, Phase.ACT, summary, 0, null, reason));
    }

    /** B: LLM 分析（PLAN），非法计划重规划一次，仍失败返回 null（表示无需拆分或降级）。 */
    private Plan analyze(ConversationCtx ctx, String goal, AgentEventPublisher publisher) {
        EngineRunResult planResult = engine.run(withSuppress(ctx, Phase.PLAN, goal), Phase.PLAN, goal).block();
        if (planResult == null || planResult.error() != null) {
            return null;
        }
        Optional<Plan> planOpt = planGenerator.parse(planResult.finalText());
        Plan plan = planOpt.orElse(null);
        if (plan != null && planValidator.isValid(plan)) {
            return plan;
        }
        // 非法计划：重规划一次（R3 闭环），仍失败返回 null 降级为「无需拆分」
        EngineRunResult replanResult = replanner.replan(ctx, goal,
                plan == null ? "无法解析计划" : String.join(";", planValidator.validate(plan))).block();
        if (replanResult != null && replanResult.error() == null) {
            return planGenerator.parse(replanResult.finalText()).orElse(null);
        }
        return null;
    }

    /** F: 逐任务执行（G: 局部重试，含指数退避；H: 重试超限提前终止）。 */
    private ExecOutcome executeAll(ConversationCtx ctx, ConversationStateManager.SessionState state,
                                   List<AgentEvent.TaskItem> tasks, AgentEventPublisher publisher, int maxRetry) {
        String sessionId = ctx.sessionId();
        StringBuilder log = new StringBuilder();
        long tokens = 0;
        int size = tasks.size();
        for (int i = 0; i < size; i++) {
            AgentEvent.TaskItem t = tasks.get(i);
            taskProgressTracker.progress(sessionId, t.taskId(),
                    AgentEvent.TaskProgressStatus.RUNNING, i, size, publisher);
            EngineRunResult r = null;
            // 记录本次尝试前的消息长度，便于回滚（P2-3）
            int snapshotSize = state.messages().size();
            // G: 异常捕获与局部重试（含首次共 maxRetry+1 次，重试间指数退避）
            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                if (attempt > 0) {
                    // P2-3：重试前回滚本任务上次尝试追加的消息，避免失败噪音污染上下文
                    state.truncateTo(snapshotSize);
                    publisher.publish(sessionId, AgentEvent.thought(sessionId,
                            "子任务「" + t.title() + "」第 " + attempt + " 次重试…"));
                    sleepQuietly(backoffMs(attempt));
                }
                state.appendMessage(UserMessage.from(t.title()));
                r = engine.run(withSuppress(ctx, Phase.ACT, t.title()), Phase.ACT, t.title()).block();
                if (r != null && r.error() == null && !(r.status() != null && r.status().equals("ask"))) {
                    break;
                }
                if (r != null && r.status() != null && r.status().equals("ask")) {
                    // P0-1：高危操作需确认，中断并说明原因
                    taskProgressTracker.progress(sessionId, t.taskId(),
                            AgentEvent.TaskProgressStatus.FAILED, i + 1, size, publisher);
                    String reason = askReason(ctx);
                    askSuspender.suspend(ctx.sessionRef(), reason, ctx.permissionLevel() == null
                            ? "unknown" : ctx.permissionLevel().getCode());
                    return new ExecOutcome(false, true, r, log, tokens);
                }
            }
            if (r == null || r.error() != null) {
                taskProgressTracker.progress(sessionId, t.taskId(),
                        AgentEvent.TaskProgressStatus.FAILED, i + 1, size, publisher);
                publisher.publish(sessionId, AgentEvent.thought(sessionId,
                        "子任务「" + t.title() + "」多次重试仍失败（" + (r == null ? "无返回" : r.error())
                                + "），超过重试上限，提前终止并告知用户。"));
                log.append('\n').append("- ").append(t.title()).append("：失败 - ")
                        .append(r == null ? "无返回" : r.error());
                return new ExecOutcome(true, false, null, log, tokens);
            }
            tokens += Math.max(0, r.tokenUsed());
            taskProgressTracker.progress(sessionId, t.taskId(),
                    AgentEvent.TaskProgressStatus.DONE, i + 1, size, publisher);
            if (r.finalText() != null && !r.finalText().isBlank()) {
                log.append('\n').append("- ").append(t.title()).append("：").append(r.finalText());
            }
        }
        return new ExecOutcome(false, false, null, log, tokens);
    }

    /** 根据权限级别给出「无法执行」的原因（P0-1 第二分支）。 */
    private String askReason(ConversationCtx ctx) {
        PermissionLevel lv = ctx.permissionLevel();
        if (lv == PermissionLevel.READ_ONLY) {
            return "当前工作区为只读权限，写/删除/执行类操作被拒绝，请调整工作区权限或确认该操作。";
        }
        if (lv == PermissionLevel.MODIFY) {
            return "当前工作区为修改权限，命令执行类操作被拒绝，请提升权限或确认该操作。";
        }
        return "该操作需要用户确认后才能执行。";
    }

    /** 指数退避毫秒数（基础退避 × 2^(attempt-1)，上限 8 秒）。 */
    private long backoffMs(int attempt) {
        long base = properties.retryBackoffMs();
        long factor = 1L << Math.min(attempt - 1, 4);
        return Math.min(base * factor, 8000L);
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 构造编排模式上下文：extra.suppressStop=true，引擎不逐次发布 stop。 */
    private ConversationCtx withSuppress(ConversationCtx base, Phase phase, String goal) {
        Map<String, Object> extra = new HashMap<>();
        if (base.extra() != null) {
            extra.putAll(base.extra());
        }
        extra.put("suppressStop", true);
        return ConversationCtx.builder()
                .sessionRef(base.sessionRef())
                .phase(phase)
                .permissionLevel(base.permissionLevel())
                .goal(goal)
                .extra(extra)
                .build();
    }

    private record ExecOutcome(boolean aborted, boolean ask, EngineRunResult askResult,
                               StringBuilder log, long tokens) {
    }
}
