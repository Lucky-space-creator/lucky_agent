package com.lucky.agent.core.service.graph;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.engine.EarlyStopPolicy;
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
import com.lucky.agent.core.service.AgentOrchestrator;
import com.lucky.agent.core.service.LoopMemoryManager;
import com.lucky.agent.core.subagent.TaskProgressTracker;
import com.lucky.agent.core.verify.VerificationChain;
import com.lucky.agent.core.verify.VerificationResult;
import com.lucky.agent.core.verify.VerificationVerdict;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * LangGraph4j 编排层（流程图语义，与 {@code Orchestrator} 等价的图实现）。
 *
 * <p>本实现在保留 DAG 表达的同时，补齐了 {@code Orchestrator} 已落地的全部决策逻辑：
 * 三重安全阀（迭代/回合/token）、独立 {@link RunBudget}（P1-2）、客观验证责任链 D/I、
 * 低置信早停（EarlyStopPolicy）、主回环记忆管理 K（{@link LoopMemoryManager}）、
 * 高危 ASK 挂起（P0-1）、重试回滚（P2-3）与单步轻量验证（P2-2）。</p>
 *
 * <p>节点图对应流程（单轮内部状态机，外层由 {@code run} 的 for 循环驱动多轮）：</p>
 * <pre>
 *   analyze(B: LLM 分析需求 + N 安全阀预检)
 *         → decide(C: 是否拆小任务)
 *             ├ 否(singleAct) → review
 *             └ 是(execute: E→F + G 局部重试/H 超限终止) → review
 *         → review(D/I: 客观校验 + J 达成度判定)
 *             ├ 达成 → summarize
 *             └ 未达成 → memory(K: 记忆管理) → 回到 analyze（外层 for 下一轮）
 *   （N: 安全阀 MAX_ITERATIONS / MAX_TURNS / MAX_BUDGET → 强制结束 → summarize）
 *   → summarize(P: 总结) → END(Q: 输出)
 * </pre>
 *
 * <p>每个节点的关键判断都通过 {@link AgentEvent#thought} 推送到对话框思考区展示；
 * 各 ACT 轮正文经 {@code content_delta} 流式输出。引擎在编排模式下不逐次发布
 * stop（{@code suppressStop=true}），收尾 stop 由会话层统一发布。</p>
 */
@Component
@ConditionalOnProperty(prefix = "core", name = "orchestrator-mode", havingValue = "langgraph")
public class LangGraphOrchestrator implements AgentOrchestrator {

    /** 状态键。 */
    private static final String K_GOAL = "goal";
    private static final String K_ITER = "iteration";
    private static final String K_PLAN = "plan";
    private static final String K_LOG = "executionLog";
    private static final String K_DONE = "done";
    private static final String K_ABORTED = "aborted";
    private static final String K_SUMMARY = "summary";
    private static final String K_REASON = "reason";
    private static final String K_CONTINUE_GOAL = "continueGoal";
    private static final String K_TOKENS = "tokens";
    private static final String K_ASK = "ask";
    private static final String K_ASK_RESULT = "askResult";

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
    private final EarlyStopPolicy earlyStopPolicy;
    private final ActScheduler actScheduler;

    public LangGraphOrchestrator(Engine engine, ConversationStateManager stateManager,
                                 PlanGenerator planGenerator, PlanValidator planValidator,
                                 Replanner replanner, TaskProgressTracker coreTaskProgressTracker,
                                 VerificationChain verificationChain, LoopMemoryManager loopMemoryManager,
                                 CoreProperties properties, AskSuspender askSuspender,
                                 EarlyStopPolicy earlyStopPolicy, ActScheduler actScheduler) {
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
        this.earlyStopPolicy = earlyStopPolicy;
        this.actScheduler = actScheduler;
    }

    /**
     * 用 LangGraph 状态图运行一次编排（复杂/简单任务统一入口，与 Orchestrator.run 等价）。
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
        // RunBudget 内部可变（Atomic），用 final 引用供节点闭包访问
        final RunBudget budget = new RunBudget(properties.runMaxTurns(), properties.runMaxBudget());

        for (int iter = 1; ; iter++) {
            // N 安全阀（循环头统一检查）：迭代次数 / 回合数 / token 预算 任一耗尽 → 强制结束并总结
            EngineRunResult tripped = checkSafetyValve(sessionId, iter, maxIter, budget,
                    accumulated, publisher);
            if (tripped != null) {
                return tripped;
            }
            budget.incrementTurn();

            Map<String, Object> initial = new HashMap<>();
            initial.put(K_GOAL, goal);
            initial.put(K_ITER, iter);
            initial.put(K_LOG, accumulated.toString());
            initial.put(K_DONE, false);
            initial.put(K_ABORTED, false);

            AgentState finalState;
            try {
                StateGraph<AgentState> graph = new StateGraph<>(
                                (org.bsc.langgraph4j.state.AgentStateFactory<AgentState>) AgentState::new)
                        .addNode("analyze", node((s) -> analyze(s, ctx, publisher)))
                        .addNode("singleAct", node((s) -> singleAct(s, ctx, publisher)))
                        .addNode("execute", node((s) -> execute(s, ctx, stateManager.session(ref), publisher)))
                        .addNode("review", node((s) -> review(s, ctx, stateManager.session(ref), publisher)))
                        .addNode("memory", node((s) -> memory(s, ctx, stateManager.session(ref), publisher)))
                        .addNode("summarize", node((s) -> summarize(s, publisher)))
                        .addEdge(GraphDefinition.START, "analyze")
                        // C: 是否拆小任务（在 decide 条件边）
                        .addConditionalEdges("analyze", edge((s) -> decideRoute(s, ctx, maxIter, publisher)),
                                Map.of("singleAct", "singleAct",
                                        "execute", "execute",
                                        "summarize", "summarize"))
                        .addEdge("singleAct", "review")
                        .addEdge("execute", "review")
                        // J: 是否达成目标（N: 安全阀也在此判定）
                        .addConditionalEdges("review", edge((s) -> reviewRoute(s, ctx, maxIter, publisher)),
                                Map.of("summarize", "summarize",
                                        "memory", "memory"))
                        .addEdge("memory", "analyze")
                        .addEdge("summarize", GraphDefinition.END);

                var compiled = graph.compile();
                // 安全阀兜底：限制图内部最大迭代次数，防止主回环死循环
                compiled.setMaxIterations(Math.max(8, maxIter * 4));
                finalState = compiled.invoke(initial).orElseThrow(
                        () -> new IllegalStateException("编排图无最终状态"));
            } catch (Exception e) {
                return EngineRunResult.error(sessionId, Phase.ACT, e.getMessage());
            }

            // 预算累计（节点内不直接引用 budget，由外层统一累加）
            Object tok = finalState.value(K_TOKENS).orElse(0L);
            if (tok instanceof Number n) {
                budget.addTokens(n.longValue());
            }

            // ASK 分支（P0-1）：单步或执行中遇高危确认 → 已挂起，中断整个编排返回
            if (Boolean.TRUE.equals(finalState.value(K_ASK).orElse(false))) {
                Object askResult = finalState.value(K_ASK_RESULT);
                return askResult instanceof EngineRunResult r ? r
                        : EngineRunResult.error(sessionId, Phase.ACT, "ASK 中断但无结果");
            }

            // 提前终止（H）：子任务失败超过重试上限
            if (Boolean.TRUE.equals(finalState.value(K_ABORTED).orElse(false))) {
                String summary = "部分子任务多次重试仍失败，已提前终止并告知。"
                        + "已完成/失败的执行情况：\n" + safeText(asStr(finalState.value(K_LOG), ""));
                return EngineRunResult.of(sessionId, Phase.ACT, summary, 0, null, "aborted");
            }

            // 达成 → 总结输出（P/Q）
            if (Boolean.TRUE.equals(finalState.value(K_DONE).orElse(false))) {
                String summary = safeText(asStr(finalState.value(K_SUMMARY), ""));
                accumulated.append(asStr(finalState.value(K_LOG), ""));
                return EngineRunResult.of(sessionId, Phase.ACT, summary,
                        budget.usedTokens(), null, "success");
            }

            // J=未达成且未超上限 → K 已写入 continueGoal，回 B 再分析（外层 for 下一轮）
            accumulated.append(asStr(finalState.value(K_LOG), ""));
            String continueGoal = safeText(asStr(finalState.value(K_CONTINUE_GOAL), ""));
            if (!continueGoal.isBlank()) {
                goal = continueGoal;
            }
        }
    }

    // ---------------- 节点动作（返回状态增量 Map） ----------------

    /** B: LLM 分析需求（PLAN，由模型判断是否需拆子任务）。 */
    private Map<String, Object> analyze(AgentState s, ConversationCtx ctx, AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        int iter = asInt(s.value(K_ITER), 0);
        Map<String, Object> out = new HashMap<>();
        out.put(K_DONE, false);
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【分析】正在分析需求并判断是否需拆分为子任务（第 " + iter + " 轮）…"));
        Plan plan = analyzePlan(ctx, asStr(s.value(K_GOAL), ctx.goal()), publisher);
        out.put(K_PLAN, plan);
        return out;
    }

    /** C=否：单次 REACT（P2-2 轻量验证）。 */
    private Map<String, Object> singleAct(AgentState s, ConversationCtx ctx, AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        String goal = asStr(s.value(K_GOAL), ctx.goal());
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【分析】模型判定无需拆分，直接执行（单次 REACT）。"));
        EngineRunResult direct = actScheduler.execute(ctx, new Plan(goal, List.of(), false)).block();
        Map<String, Object> out = new HashMap<>();
        if (direct == null) {
            out.put(K_REASON, "引擎无返回结果");
            out.put(K_SUMMARY, "引擎无返回结果");
            out.put(K_TOKENS, 0L);
            return out;
        }
        if (direct.status() != null && direct.status().equals("ask")) {
            // P0-1：非 FULL 权限下高危操作需确认，挂起并中断
            String reason = askReason(ctx);
            askSuspender.suspend(ctx.sessionRef(), reason, ctx.permissionLevel() == null
                    ? "unknown" : ctx.permissionLevel().getCode());
            out.put(K_ASK, true);
            out.put(K_ASK_RESULT, direct);
            return out;
        }
        if (direct.error() != null) {
            out.put(K_REASON, direct.error());
            out.put(K_SUMMARY, direct.error());
            out.put(K_TOKENS, direct.tokenUsed());
            return out;
        }
        // P2-2：单步任务也走轻量验证（无客观声明时仅主观判定）
        VerificationVerdict v = verificationChain.verify(null, ctx, goal,
                direct.finalText(), publisher);
        out.put(K_LOG, "\n- 直接执行：" + safeText(direct.finalText()));
        out.put(K_TOKENS, direct.tokenUsed());
        if (v.done()) {
            out.put(K_DONE, true);
            out.put(K_SUMMARY, v.summary());
        } else {
            out.put(K_REASON, "单步执行未完全达成");
            out.put(K_CONTINUE_GOAL, v.continueGoal());
            out.put(K_LOG, out.get(K_LOG) + v.evidenceText());
            // K：记忆管理（上下文压缩 + 长期记忆沉淀）
            loopMemoryManager.manage(ctx, stateManager.session(ctx.sessionRef()),
                    safeText(direct.finalText()) + v.evidenceText(), publisher);
        }
        return out;
    }

    /** E→F: 逐任务执行（G: 局部重试 / H: 超限提前终止，含 P2-3 重试回滚）。 */
    private Map<String, Object> execute(AgentState s, ConversationCtx ctx,
                                        ConversationStateManager.SessionState state,
                                        AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        Plan plan = (Plan) s.value(K_PLAN).orElse(null);
        Map<String, Object> out = new HashMap<>();
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            out.put(K_LOG, asStr(s.value(K_LOG), ""));
            out.put(K_TOKENS, 0L);
            return out;
        }
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【分析】拆分为 " + plan.steps().size() + " 个子任务，逐一执行。"));
        List<AgentEvent.TaskItem> tasks = plan.steps().stream()
                .map(step -> new AgentEvent.TaskItem("s" + step.id(), step.desc()))
                .toList();
        taskProgressTracker.plan(sessionId, tasks, publisher);

        StringBuilder log = new StringBuilder();
        int size = tasks.size();
        int maxRetry = properties.orchestratorMaxRetries();
        long tokens = 0;
        for (int i = 0; i < size; i++) {
            AgentEvent.TaskItem t = tasks.get(i);
            taskProgressTracker.progress(sessionId, t.taskId(),
                    AgentEvent.TaskProgressStatus.RUNNING, i, size, publisher);
            EngineRunResult r = null;
            // 记录本次尝试前的消息长度，便于回滚（P2-3）
            int snapshotSize = state.messages().size();
            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                if (attempt > 0) {
                    // P2-3：重试前回滚本任务上次尝试追加的消息
                    state.truncateTo(snapshotSize);
                    publisher.publish(sessionId, AgentEvent.thought(sessionId,
                            "子任务「" + t.title() + "」第 " + attempt + " 次重试…"));
                    sleepQuietly(backoffMs(attempt));
                }
                state.appendMessage(UserMessage.from(t.title()));
                r = engine.run(withSuppress(ctx, Phase.ACT, t.title()), Phase.ACT, t.title()).block();
                if (r != null && r.error() == null
                        && !(r.status() != null && r.status().equals("ask"))) {
                    break;
                }
                if (r != null && r.status() != null && r.status().equals("ask")) {
                    // P0-1：高危操作需确认，挂起并中断
                    taskProgressTracker.progress(sessionId, t.taskId(),
                            AgentEvent.TaskProgressStatus.FAILED, i + 1, size, publisher);
                    String reason = askReason(ctx);
                    askSuspender.suspend(ctx.sessionRef(), reason, ctx.permissionLevel() == null
                            ? "unknown" : ctx.permissionLevel().getCode());
                    out.put(K_ASK, true);
                    out.put(K_ASK_RESULT, r);
                    return out;
                }
            }
            if (r == null || r.error() != null) {
                taskProgressTracker.progress(sessionId, t.taskId(),
                        AgentEvent.TaskProgressStatus.FAILED, i + 1, size, publisher);
                publisher.publish(sessionId, AgentEvent.thought(sessionId,
                        "子任务「" + t.title() + "」多次重试仍失败（"
                                + (r == null ? "无返回" : r.error())
                                + "），超过重试上限，提前终止并告知用户。"));
                log.append('\n').append("- ").append(t.title()).append("：失败 - ")
                        .append(r == null ? "无返回" : r.error());
                out.put(K_ABORTED, true);
                break;
            }
            tokens += Math.max(0, r.tokenUsed());
            taskProgressTracker.progress(sessionId, t.taskId(),
                    AgentEvent.TaskProgressStatus.DONE, i + 1, size, publisher);
            if (r.finalText() != null && !r.finalText().isBlank()) {
                log.append('\n').append("- ").append(t.title()).append("：").append(r.finalText());
            }
        }
        out.put(K_LOG, asStr(s.value(K_LOG), "") + log);
        out.put(K_TOKENS, tokens);
        out.put(K_ABORTED, out.getOrDefault(K_ABORTED, false));
        return out;
    }

    /** D/I: 客观验证 + 达成度判定（客观证据优先于主观结论）。 */
    private Map<String, Object> review(AgentState s, ConversationCtx ctx,
                                       ConversationStateManager.SessionState state,
                                       AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        String goal = asStr(s.value(K_GOAL), ctx.goal());
        String execLog = asStr(s.value(K_LOG), "");
        Map<String, Object> out = new HashMap<>();
        out.put(K_DONE, false);
        if (Boolean.TRUE.equals(s.value(K_ABORTED).orElse(false))) {
            out.put(K_REASON, "子任务失败提前终止");
            out.put(K_SUMMARY, "部分子任务多次重试仍失败，已提前终止并告知。已完成/失败的执行情况：\n" + execLog);
            out.put(K_TOKENS, 0L);
            return out;
        }
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【复查】执行客观验证并核对整体目标是否达成…"));
        Plan plan = (Plan) s.value(K_PLAN).orElse(null);
        VerificationVerdict verdict = verificationChain.verify(plan, ctx, goal, execLog, publisher);
        out.put(K_TOKENS, 0L);
        out.put(K_LOG, execLog);

        if (verdict.done()) {
            out.put(K_DONE, true);
            out.put(K_SUMMARY, verdict.summary());
            return out;
        }

        // 低置信早停（EarlyStopPolicy）：客观验证通过率过低 → 转 ASK 让用户决策
        double confidence = evidenceConfidence(verdict);
        if (earlyStopPolicy.shouldStop(confidence)) {
            String reason = "多次执行后仍无法客观验证达成，需你明确指示后续方向。";
            askSuspender.suspend(ctx.sessionRef(), reason, "low_confidence");
            out.put(K_ASK, true);
            out.put(K_ASK_RESULT, EngineRunResult.of(sessionId, Phase.ACT,
                    verdict.summary(), 0, null, "ask"));
            return out;
        }

        String continueGoal = verdict.continueGoal();
        out.put(K_REASON, "检测到未完成项");
        out.put(K_CONTINUE_GOAL, continueGoal);
        // K：记忆管理（上下文压缩 + 长期记忆沉淀）
        loopMemoryManager.manage(ctx, state, execLog + verdict.evidenceText(), publisher);
        return out;
    }

    /** K: 记忆管理（回环到 analyze 前，已在上游 review 节点执行 manage；此处仅推进 goal）。 */
    private Map<String, Object> memory(AgentState s, ConversationCtx ctx,
                                       ConversationStateManager.SessionState state, AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        Map<String, Object> out = new HashMap<>();
        String continueGoal = safeText(asStr(s.value(K_CONTINUE_GOAL), ""));
        if (!continueGoal.isBlank()) {
            out.put(K_GOAL, continueGoal);
        }
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【复查】" + asStr(s.value(K_REASON), "未完全达成")
                        + "，进行记忆/上下文管理后进入下一轮分析。"));
        return out;
    }

    /** P: 总结。 */
    private Map<String, Object> summarize(AgentState s, AgentEventPublisher publisher) {
        Map<String, Object> out = new HashMap<>();
        String summary = safeText(asStr(s.value(K_SUMMARY), ""));
        if (summary.isBlank()) {
            String execLog = asStr(s.value(K_LOG), "");
            String reason = asStr(s.value(K_REASON), "");
            summary = reason.isBlank()
                    ? execLog
                    : reason + "。已完成的执行情况：\n" + execLog;
        }
        out.put(K_SUMMARY, summary);
        return out;
    }

    // ---------------- 条件边路由 ----------------

    /** C: 是否拆小任务 / N: 安全阀。 */
    private String decideRoute(AgentState s, ConversationCtx ctx, int maxIter, AgentEventPublisher publisher) {
        int iter = asInt(s.value(K_ITER), 0);
        if (iter > maxIter) {
            publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(),
                    "已达最大迭代次数（" + maxIter + "），安全阀触发，强制结束。"));
            return "summarize";
        }
        Plan plan = (Plan) s.value(K_PLAN).orElse(null);
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return "singleAct";
        }
        return "execute";
    }

    /** J: 是否达成目标；N: 安全阀。 */
    private String reviewRoute(AgentState s, ConversationCtx ctx, int maxIter, AgentEventPublisher publisher) {
        if (Boolean.TRUE.equals(s.value(K_DONE).orElse(false))) {
            return "summarize";
        }
        int iter = asInt(s.value(K_ITER), 0);
        if (iter >= maxIter) {
            return "summarize";
        }
        return "memory";
    }

    // ---------------- 辅助 ----------------

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
        long passed = evidence.stream()
                .filter(r -> r.objective() && r.passed() && !r.skipped()).count();
        return (double) passed / objective;
    }

    /** N 安全阀：迭代次数 / 回合数 / token 预算三重检查（返回兜底结果或 null）。 */
    private EngineRunResult checkSafetyValve(String sessionId, int iter, int maxIter,
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
            return null;
        }
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【安全阀】" + detail + "，强制结束并总结当前进度。"));
        String summary = "任务执行" + detail + "，未能完全达成目标。已完成的子任务与结果如下：\n" + accumulated;
        return EngineRunResult.of(sessionId, Phase.ACT, summary, 0, null, reason);
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

    /** B: LLM 分析（PLAN），非法计划重规划一次，仍失败返回 null（降级为「无需拆分」）。 */
    private Plan analyzePlan(ConversationCtx ctx, String goal, AgentEventPublisher publisher) {
        EngineRunResult planResult = engine.run(withSuppress(ctx, Phase.PLAN, goal), Phase.PLAN, goal).block();
        if (planResult == null || planResult.error() != null) {
            return null;
        }
        Plan plan = planGenerator.parse(planResult.finalText()).orElse(null);
        if (plan != null && planValidator.isValid(plan)) {
            return plan;
        }
        EngineRunResult replanResult = replanner.replan(ctx, goal,
                plan == null ? "无法解析计划" : String.join(";", planValidator.validate(plan))).block();
        if (replanResult != null && replanResult.error() == null) {
            return planGenerator.parse(replanResult.finalText()).orElse(null);
        }
        return null;
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

    @SuppressWarnings("unchecked")
    private String asStr(Optional<Object> v, String dflt) {
        return v.isPresent() ? (String) v.get() : dflt;
    }

    private int asInt(Optional<Object> v, int dflt) {
        return v.isPresent() && v.get() instanceof Number n ? n.intValue() : dflt;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    // ---------------- 适配器：同步动作包装为 CompletableFuture 节点/边 ----------------

    @FunctionalInterface
    private interface SyncNode {
        Map<String, Object> apply(AgentState s);
    }

    private org.bsc.langgraph4j.action.AsyncNodeAction<AgentState> node(SyncNode action) {
        return (s) -> CompletableFuture.completedFuture(action.apply(s));
    }

    @FunctionalInterface
    private interface SyncEdge {
        String apply(AgentState s);
    }

    private org.bsc.langgraph4j.action.AsyncEdgeAction<AgentState> edge(SyncEdge action) {
        return (s) -> CompletableFuture.completedFuture(action.apply(s));
    }
}
