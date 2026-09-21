package com.lucky.agent.core.service;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.repository.SubAgentIntent;
import com.lucky.agent.core.repository.SubAgentResult;
import com.lucky.agent.core.util.engine.EarlyStopPolicy;
import com.lucky.agent.core.util.engine.StepLimitGuard;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.PlanValidator;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.util.planactask.ActScheduler;
import com.lucky.agent.core.util.planactask.AskSuspender;
import com.lucky.agent.core.util.planactask.PlanGenerator;
import com.lucky.agent.core.util.planactask.Replanner;
import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.core.util.runtime.RunBudget;
import com.lucky.agent.core.util.subagent.TaskProgressTracker;
import com.lucky.agent.core.util.subagent.TaskScheduler;
import com.lucky.agent.core.util.verify.VerificationChain;
import com.lucky.agent.core.util.verify.VerificationResult;
import com.lucky.agent.core.util.verify.VerificationVerdict;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "core", name = "orchestrator-mode",
        havingValue = "reactor", matchIfMissing = true)
public class Orchestrator implements AgentOrchestrator {

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
    /** 子代理调度器（仅 core.subagent-enabled=true 且有 subagents 声明时使用；否则不参与）。 */
    private final TaskScheduler subAgentScheduler;

    public Orchestrator(Engine engine, ConversationStateManager stateManager,
                        PlanGenerator planGenerator, PlanValidator planValidator, Replanner replanner,
                        TaskProgressTracker coreTaskProgressTracker,
                        VerificationChain verificationChain,
                        LoopMemoryManager loopMemoryManager,
                        CoreProperties properties,
                        AskSuspender askSuspender,
                        StepLimitGuard stepLimitGuard,
                        EarlyStopPolicy earlyStopPolicy,
                        ActScheduler actScheduler,
                        TaskScheduler subAgentScheduler) {
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
        this.subAgentScheduler = subAgentScheduler;
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
        // 循环守卫：记录上一轮未达成结论的归一化文本，连续两轮一致判定为死循环/重复
        String lastSummaryNorm = null;
        // 同上，但针对「用户实际看到的回答文本」：验证结论 summary 常含易变动的证据文本，
        // 仅比对 summary 会漏掉「回答一字不差、结论措辞略变」的重复回答（Issue 1 根因）
        String lastAnswerNorm = null;

        for (int iter = 1; ; iter++) {
            // 用户已取消：停止开启新轮次（软取消，正在进行的单次调用放行）
            if (stateManager.session(ref).cancelRequested()) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId, "已取消，停止本轮执行。"));
                return EngineRunResult.of(sessionId, Phase.ACT, null, budget.usedTokens(), null, "cancelled");
            }
            // N 安全阀（循环头统一检查）：迭代次数 / 回合数 / token 预算 任一耗尽 → 强制结束并总结
            Optional<EngineRunResult> tripped = checkSafetyValve(
                    sessionId, iter, maxIter, budget, accumulated, publisher);
            if (tripped.isPresent()) {
                return tripped.get();
            }

            // B: LLM 分析需求（PLAN 阶段产出结构化计划，由模型判断是否需拆子任务/启动子代理）
            publisher.publish(sessionId, AgentEvent.progress(sessionId,
                    "【分析】正在分析需求并判断是否需拆分为子任务/启动子代理（第 " + iter + " 轮）…"));
            Analysis analysis = analyze(ctx, goal, publisher);
            Plan plan = analysis.plan();
            List<SubAgentIntent> subIntents = analysis.subAgents();
            budget.incrementTurn();

            // PLAN 阶段模型要么直接向用户征询信息（询问型回答），要么产出可执行计划。
            // 询问型回答（如「请提供报错信息」「请回复……」）一次问完即停，
            // 不再强制拆步骤/继续下一轮，从根上杜绝「用户没提问却反复收到提问」。
            if (isAskingReply(analysis.rawText())) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【分析】模型向用户征询信息，等待用户回复后再继续。"));
                return EngineRunResult.of(sessionId, Phase.PLAN, analysis.rawText(),
                        budget.usedTokens(), null, "ask");
            }

            // C1: 是否声明了「隔离子代理」（仅 core.subagent-enabled=true 时生效）
            boolean hasSubAgents = properties.subagentEnabled()
                    && subIntents != null && !subIntents.isEmpty();
            boolean hasSteps = plan != null && plan.steps() != null && !plan.steps().isEmpty();

            // C2=否且无子代理 → 单 Agent 直接执行（默认单 agent，P2-1）
            if (!hasSteps && !hasSubAgents) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【分析】模型判定无需拆分/子代理，单 Agent 直接执行。"));
                // 直连执行同样走 suppressStop：编排期内引擎不发 stop，收尾由会话层统一发布
                EngineRunResult direct = actScheduler.execute(
                        withSuppress(ctx, Phase.ACT, goal), new Plan(goal, List.of(), false)).block();
                if (direct != null) {
                    budget.addTokens(direct.tokenUsed());
                }
                if (direct == null) {
                    return EngineRunResult.error(sessionId, Phase.ACT, "引擎无返回结果");
                }
                if (direct.status() != null && direct.status().equals("ask")) {
                    String reason = askReason(ctx);
                    askSuspender.suspend(ref, reason, ctx.permissionLevel() == null
                            ? "unknown" : ctx.permissionLevel().getCode());
                    return direct;
                }
                // 条件选择（options）：选项事件已由引擎发布（含回退正文），此处挂起等待用户选择；
                // 超时由会话层/挂起器兜底自动选中推荐项，故与 ask 一样落一次挂起记录以便重启恢复。
                if (direct.status() != null && direct.status().equals("options")) {
                    askSuspender.suspend(ref, "conditional-options",
                            ctx.permissionLevel() == null ? "unknown" : ctx.permissionLevel().getCode());
                    return direct;
                }
                if (direct.status() != null && direct.status().equals("cancelled")) {
                    return direct;
                }
                if (direct.error() != null) {
                    return direct;
                }
                // 询问型结论（模型已向用户征询信息，本轮无工具动作）：一次问完即停，
                // 先于验证判定返回（少跑一轮 judge 模型调用），不进入
                // 「continueGoal → 再分析」死循环（重复提问根因之一）
                if (isAskingReply(direct.finalText())) {
                    return EngineRunResult.of(sessionId, Phase.ACT, direct.finalText(),
                            direct.tokenUsed(), direct.model(), "ask");
                }
                // P2-2：单步任务也走轻量验证（无客观声明时仅主观判定）
                VerificationVerdict v = verificationChain.verify(null, ctx, goal,
                        direct.finalText(), publisher);
                budget.incrementTurn();
                if (v.done()) {
                    return EngineRunResult.of(sessionId, Phase.ACT, v.summary(),
                            direct.tokenUsed(), direct.model(), "success");
                }
                // 验证总结亦为询问型：同样一次问完即停
                if (isAskingReply(v.summary())) {
                    String ask = !v.summary().isBlank() ? v.summary() : direct.finalText();
                    return EngineRunResult.of(sessionId, Phase.ACT, ask,
                            direct.tokenUsed(), direct.model(), "ask");
                }
                // 循环守卫：连续两轮未达成结论一致 → 模型在重复/反复询问，提前结束避免死循环
                // 增强：直接比对「用户实际看到的回答」文本，防止结论措辞略变但回答一字不差的重复
                if (iter >= 3 && stuckLoop(iter, lastAnswerNorm, direct.finalText())) {
                    publisher.publish(sessionId, AgentEvent.progress(sessionId,
                            "【安全阀】连续两轮回答内容一致，判定为重复回答，提前结束本轮。"));
                    return EngineRunResult.of(sessionId, Phase.ACT, direct.finalText(),
                            direct.tokenUsed(), direct.model(), "stuck");
                }
                lastAnswerNorm = normalize(direct.finalText());
                if (stuckLoop(iter, lastSummaryNorm, v.summary())) {
                    publisher.publish(sessionId, AgentEvent.progress(sessionId,
                            "【安全阀】连续两轮结论一致，模型在重复/反复询问，提前结束本轮。"));
                    return EngineRunResult.of(sessionId, Phase.ACT, v.summary(),
                            direct.tokenUsed(), direct.model(), "stuck");
                }
                lastSummaryNorm = normalize(v.summary());
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【复查】单步执行未完全达成，进入下一轮分析。"));
                loopMemoryManager.manage(ctx, stateManager.session(ref),
                        direct.finalText() + v.evidenceText(), publisher);
                goal = v.continueGoal();
                continue;
            }

            // 本轮执行日志（步骤 + 子代理合并，供验证/记忆/总结）
            StringBuilder roundLog = new StringBuilder();
            long roundTokens = 0;

            // C1=是：先启动隔离子代理，收集子问题结果（独立 spec/会话/摘要回灌）
            if (hasSubAgents) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【分析】检测到高复杂度子问题，启动 " + subIntents.size() + " 个隔离子代理并行分析…"));
                // 推任务计划事件，供前端展示子代理清单
                List<AgentEvent.TaskItem> subTasks = subIntents.stream()
                        .map(s -> new AgentEvent.TaskItem(s.id(), (s.name() == null ? s.id() : s.name())
                                + "（子代理）")).toList();
                taskProgressTracker.plan(sessionId, subTasks, publisher);

                List<SubAgentSpec> specs = new java.util.ArrayList<>();
                List<String> tasks = new java.util.ArrayList<>();
                for (SubAgentIntent s : subIntents) {
                    specs.add(SubAgentSpec.builder()
                            .id(s.id())
                            .name(s.name())
                            .description("主 Agent 委托的子代理：" + s.task())
                            .tools(s.tools())
                            .disallowedTools(s.disallowedTools())
                            .permissionMode(s.permissionMode())
                            .summaryOnly(s.summaryOnly())
                            .isolation("session")
                            .build());
                    tasks.add(s.task());
                }
                // 串行调度（每个子代理独立 session/隔离）；并行版 scheduleParallel 可选
                List<SubAgentResult> results = subAgentScheduler
                        .scheduleSerial(specs, tasks, ref.workspaceId(), sessionId)
                        .collectList().block();
                // 进度：全部标记完成
                for (int i = 0; i < subIntents.size(); i++) {
                    taskProgressTracker.progress(sessionId, subIntents.get(i).id(),
                            AgentEvent.TaskProgressStatus.DONE, i + 1, subIntents.size(), publisher);
                }
                if (results != null) {
                    StringBuilder agg = new StringBuilder();
                    for (int i = 0; i < results.size() && i < subIntents.size(); i++) {
                        SubAgentResult r = results.get(i);
                        String name = subIntents.get(i).name() == null ? r.subAgentId() : subIntents.get(i).name();
                        agg.append("\n- 子代理[").append(name).append("]：")
                                .append(r.success() ? r.summary() : ("失败 - " + r.summary()));
                    }
                    // 子代理摘要回灌：仅追加为普通消息供后续验证/总结参考，不让子代理污染主上下文权限
                    if (!agg.isEmpty()) {
                        stateManager.session(ref).appendMessage(
                                UserMessage.from("【子代理结果】" + agg));
                    }
                    roundLog.append(agg);
                }
                // 子代理阶段已把隔离分析做完；若有显式步骤则继续由主 Agent 细化执行
            }

            // C2=是（且还有步骤）：核心 Agent 把剩余步骤拆开串行执行（D10「拆分成步骤」）
            if (hasSteps) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【分析】拆分为 " + plan.steps().size() + " 个步骤执行。"));
                List<AgentEvent.TaskItem> tasks = plan.steps().stream()
                        .map(s -> new AgentEvent.TaskItem("s" + s.id(), s.desc()))
                        .toList();
                taskProgressTracker.plan(sessionId, tasks, publisher);
                ExecOutcome out = executeAll(ctx, stateManager.session(ref), tasks,
                        publisher, properties.orchestratorMaxRetries());
                roundTokens += out.tokens();
                if (out.ask()) {
                    return out.askResult();
                }
                if (out.aborted()) {
                    accumulated.append(out.log());
                    return EngineRunResult.of(sessionId, Phase.ACT,
                            "部分步骤多次重试仍失败，已提前终止并告知。已完成/失败执行情况：\n" + accumulated,
                            0, null, "aborted");
                }
                roundLog.append(out.log());
            }
            budget.addTokens(roundTokens);
            accumulated.append(roundLog);
            // 执行中用户取消：透传取消结果，不进入验证/续轮
            if (stateManager.session(ref).cancelRequested()) {
                return EngineRunResult.of(sessionId, Phase.ACT, null, roundTokens, null, "cancelled");
            }

            // 执行日志本身为询问型回答（步骤输出即「请回复/请提供…」）：一次问完即停，
            // 不进入验证循环（验证判定会当作「未达成」继续续轮 → 重复提问）
            if (isAskingReply(roundLog.toString())) {
                return EngineRunResult.of(sessionId, Phase.ACT, roundLog.toString().trim(),
                        roundTokens, null, "ask");
            }

            // D → I: 客观验证 + 达成度判定（仅当存在可客观校验的步骤；子代理/单代理走主观兜底）
            VerificationVerdict verdict = verificationChain.verify(
                    hasSteps ? plan : null, ctx, goal, roundLog.toString(), publisher);
            budget.incrementTurn();

            if (verdict.done()) {
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        roundTokens, null, "success");
            }

            // 询问型结论：一次问完即停，不再 continueGoal 续轮（防「重复提问」死循环）
            if (isAskingReply(verdict.summary())) {
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        roundTokens, null, "ask");
            }

            // 循环守卫：增强为「回答文本 OR 验证结论」任一连续两轮一致即提前终止，
            // 防止结论措辞略变但回答一字不差的重复回答（Issue 1 根因）
            String thisAnswer = roundLog == null ? "" : roundLog.toString().trim();
            if (iter >= 3 && stuckLoop(iter, lastAnswerNorm, thisAnswer)) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【安全阀】连续两轮回答内容一致，判定为重复回答，提前结束本轮。"));
                return EngineRunResult.of(sessionId, Phase.ACT, thisAnswer,
                        roundTokens, null, "stuck");
            }
            lastAnswerNorm = normalize(thisAnswer);
            if (stuckLoop(iter, lastSummaryNorm, verdict.summary())) {
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
                        "【安全阀】连续两轮结论一致，模型在重复/反复询问，提前结束本轮。"));
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        roundTokens, null, "stuck");
            }
            lastSummaryNorm = normalize(verdict.summary());

            double confidence = evidenceConfidence(verdict);
            if (earlyStopPolicy.shouldStop(confidence)) {
                String reason = "多次执行后仍无法客观验证达成，需你明确指示后续方向。";
                askSuspender.suspend(ref, reason, "low_confidence");
                return EngineRunResult.of(sessionId, Phase.ACT, verdict.summary(),
                        roundTokens, null, "ask");
            }

            publisher.publish(sessionId, AgentEvent.progress(sessionId,
                    "【复查】仍有未完成项，进行记忆/上下文管理后进入下一轮分析（第 " + iter + " 轮已完成）。"));
            loopMemoryManager.manage(ctx, stateManager.session(ref),
                    roundLog.toString() + verdict.evidenceText(), publisher);
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
     * 循环守卫：连续两轮未达成结论（归一化文本）一致 → 判定模型陷入重复/死循环。
     *
     * <p>典型场景：模型反复输出「请告诉我你的项目路径」这类索取信息型结论，客观验证又判定
     * 未达成，若不拦截会一直重跑直到安全阀；此处第二轮结论与第一轮一致即提前终止。</p>
     */
    private boolean stuckLoop(int iter, String lastSummaryNorm, String summary) {
        if (iter <= 1 || summary == null || summary.isBlank()) {
            return false;
        }
        String norm = normalize(summary);
        return !norm.isEmpty() && norm.equals(lastSummaryNorm);
    }

    /** 归一化结论文本：小写 + 去标点/空白（P2-3，对标点不敏感：'请告诉我项目路径。' 与
     *  '请告诉我项目路径？' 视为同一结论），用于检测连续两轮是否重复同一结论。 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\p{P}\\p{Z}\\p{C}]+", " ").trim();
    }

    /**
     * 询问型结论判定：模型本轮未取得实质进展、以「向用户征询信息」收尾时，
     * 一次问完即停（返回 ask 等待用户输入），不进入「未达成 → continueGoal → 再分析」死循环，
     * 从根上杜绝「用户没提问却反复收到提问」的重复提问现象。
     */
    private boolean isAskingReply(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        // 以问句收尾
        if (trimmed.matches(".*[？?]\\s*$")) {
            return true;
        }
        // 短文本且以「请/需要/麻烦/能否」开头索取信息（如「请回复「开始执行」」）
        if (trimmed.length() <= 60 && trimmed.matches("^(请|需要|麻烦|你先|请先|能否|可以).*")) {
            return true;
        }
        // 明确征询信息的关键词（覆盖规划/执行两阶段常见的索取信息表达）
        String[] keywords = {"请回复", "请提供", "请补充", "请确认", "需要你", "请选择", "等待你",
                "请告诉", "请告知", "请给", "请回答", "请明确", "要我提供", "请你提供", "能否提供", "可以告诉我"};
        for (String k : keywords) {
            if (trimmed.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 该结果是否为「需挂起等待用户」（ask 或 options）——子任务循环据此中断后续步骤。 */
    private boolean isSuspending(EngineRunResult r) {
        return isOptions(r) || (r.status() != null && r.status().equals("ask"));
    }

    /** 条件选择（options）结果判定：模型输出了选项块，须挂起等待用户选择。 */
    private boolean isOptions(EngineRunResult r) {
        return r != null && r.status() != null && r.status().equals("options");
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
        publisher.publish(sessionId, AgentEvent.progress(sessionId,
                "【安全阀】" + detail + "，强制结束并总结当前进度。"));
        String summary = "任务执行" + detail + "，未能完全达成目标。已完成的子任务与结果如下：\n" + accumulated;
        return Optional.of(EngineRunResult.of(sessionId, Phase.ACT, summary, 0, null, reason));
    }

    /** B: LLM 分析（PLAN），非法计划重规划一次；返回「步骤 + 子代理声明」两类决策。 */
    private Analysis analyze(ConversationCtx ctx, String goal, AgentEventPublisher publisher) {
        EngineRunResult planResult = engine.run(withSuppress(ctx, Phase.PLAN, goal), Phase.PLAN, goal).block();
        if (planResult == null || planResult.error() != null) {
            return new Analysis(null, List.of(), "");
        }
        String raw = planResult.finalText() == null ? "" : planResult.finalText();
        Optional<Plan> planOpt = planGenerator.parse(raw);
        Plan plan = planOpt.orElse(null);
        List<SubAgentIntent> subagents = properties.subagentEnabled()
                ? planGenerator.parseSubagents(raw) : List.of();
        if (plan != null && planValidator.isValid(plan)) {
            return new Analysis(plan, subagents, raw);
        }
        // 计划非法（或无步骤）但声明了子代理：子代理仍是有效决策，优先保留
        if (!subagents.isEmpty()) {
            return new Analysis(plan, subagents, raw);
        }
        // 非法计划：重规划一次（R3 闭环），仍失败返回空降级为「无需拆分」
        EngineRunResult replanResult = replanner.replan(withSuppress(ctx, Phase.PLAN, goal), goal,
                plan == null ? "无法解析计划" : String.join(";", planValidator.validate(plan))).block();
        if (replanResult != null && replanResult.error() == null) {
            String replanRaw = replanResult.finalText() == null ? "" : replanResult.finalText();
            Plan replan = planGenerator.parse(replanRaw).orElse(null);
            List<SubAgentIntent> replanSubs = properties.subagentEnabled()
                    ? planGenerator.parseSubagents(replanRaw) : List.of();
            if (replan != null && planValidator.isValid(replan)) {
                return new Analysis(replan, replanSubs, replanRaw);
            }
            if (!replanSubs.isEmpty()) {
                return new Analysis(replan, replanSubs, replanRaw);
            }
        }
        return new Analysis(null, List.of(), raw);
    }

    /** F: 逐任务执行（G: 局部重试，含指数退避；H: 重试超限提前终止）。 */
    private ExecOutcome executeAll(ConversationCtx ctx, ConversationStateManager.SessionState state,
                                   List<AgentEvent.TaskItem> tasks, AgentEventPublisher publisher, int maxRetry) {
        String sessionId = ctx.sessionId();
        StringBuilder log = new StringBuilder();
        long tokens = 0;
        int size = tasks.size();
        for (int i = 0; i < size; i++) {
            // 用户已取消：不再继续调度剩余子任务（执行中的单次调用由引擎边界放行收尾）
            if (state.cancelRequested()) {
                return new ExecOutcome(false, false, null, log, tokens);
            }
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
                    publisher.publish(sessionId, AgentEvent.progress(sessionId,
                            "子任务「" + t.title() + "」第 " + attempt + " 次重试…"));
                    sleepQuietly(backoffMs(attempt));
                }
                state.appendMessage(UserMessage.from(t.title()));
                r = engine.run(withSuppress(ctx, Phase.ACT, t.title()), Phase.ACT, t.title()).block();
                if (r != null && r.error() == null && !isSuspending(r)) {
                    break;
                }
                if (r != null && isOptions(r)) {
                    // 条件选择：同 ask，中断后续步骤并挂起等待用户选择（选项事件已由引擎发布）
                    taskProgressTracker.progress(sessionId, t.taskId(),
                            AgentEvent.TaskProgressStatus.FAILED, i + 1, size, publisher);
                    askSuspender.suspend(ctx.sessionRef(), "conditional-options",
                            ctx.permissionLevel() == null ? "unknown" : ctx.permissionLevel().getCode());
                    return new ExecOutcome(false, true, r, log, tokens);
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
                publisher.publish(sessionId, AgentEvent.progress(sessionId,
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

    /** B 分析结果：步骤计划 + 子代理声明 + PLAN 原始文本（询问型判定用）。 */
    private record Analysis(Plan plan, List<SubAgentIntent> subAgents, String rawText) {
    }
}
