package com.lucky.agent.core.runtime.loop;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.runtime.RuntimeSession;
import com.lucky.agent.core.runtime.RuntimeSessionFactory;
import com.lucky.agent.core.runtime.contract.ExecutionMetrics;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.gateway.ModelGateway;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.runtime.strategy.StrategyPlugin;
import com.lucky.agent.core.service.AgentOrchestrator;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 薄主循环（Thin Agent Loop）：主体只做三步 —— <b>调 LLM → 执行工具/子代理 → 回填观察</b>。
 *
 * <p>与旧 {@code Orchestrator.run()}（约 240 行、混 10+ 职责）相比，本类中不存在任何
 * 拆解、验证、记忆、卡死守卫或安全阀的<b>分支判断</b>：</p>
 * <ul>
 *   <li>预算/权限/重试/压缩/召回/日志 → {@link com.lucky.agent.core.runtime.middleware.Middleware} 链；</li>
 *   <li>模型调用的重试退避与熔断 → {@link ModelGateway} 装饰链（与 langgraph 主循环共用）；</li>
 *   <li>拆解与子代理调度 → {@code decompose} / {@code spawn_sub_agent} 工具 + {@link StrategyPlugin}；</li>
 *   <li>达成度判定、卡死守卫、记忆沉淀/压缩、早停 → {@link LoopController}（与 langgraph 主循环共用）；</li>
 *   <li>会话引导（预算/上下文/中间件生命周期）→ {@link RuntimeSessionFactory}（与 langgraph 主循环共用）。</li>
 * </ul>
 *
 * <p>新增任何关注点都无需改动本类——这正是「薄主循环 + 厚运行时」的收益。</p>
 *
 * <p>通过 {@code core.orchestrator-mode=thin} 启用；默认仍走 {@code reactor}（灰度/strangler 迁移）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "core", name = "orchestrator-mode", havingValue = "thin")
public class ThinAgentLoop implements AgentOrchestrator {

    private final ModelGateway modelGateway;
    private final AgentRuntime runtime;
    private final LoopController controller;
    private final ConversationStateManager stateManager;
    private final RuntimeSessionFactory sessions;

    public ThinAgentLoop(ModelGateway modelGateway, AgentRuntime runtime, LoopController controller,
                         ConversationStateManager stateManager, RuntimeSessionFactory sessions) {
        this.modelGateway = modelGateway;
        this.runtime = runtime;
        this.controller = controller;
        this.stateManager = stateManager;
        this.sessions = sessions;
    }

    @Override
    public EngineRunResult run(SessionRef ref, ConversationCtx ctx, AgentEventPublisher publisher) {
        RuntimeSession session = sessions.open(ref, ctx, publisher, runtime);
        RuntimeContext rc = session.context();
        MiddlewareContext loopCtx = session.loopContext();

        String goal = ctx.goal();
        StringBuilder observations = new StringBuilder();

        try {
            for (int iter = 1; iter <= controller.maxIterations(); iter++) {
                rc.attribute("iteration", iter);

                if (stateManager.session(ref).cancelRequested()) {
                    return EngineRunResult.of(ref.sessionId(), Phase.ACT, "已取消，停止本轮执行。",
                            session.global().usedTokens(), null, "cancelled");
                }

                // ── 第 1 步：调 LLM（韧性由 ModelGateway 承担；横切关注点由中间件链承担） ──
                loopCtx.result(null);
                rc.trace(rc.trace().child("llm-" + iter));
                if (!runtime.beforeLlm(loopCtx)) {
                    return EngineRunResult.of(ref.sessionId(), Phase.ACT,
                            "预算耗尽，强制结束并总结当前进度：\n" + observations,
                            session.global().usedTokens(), null, "budget_exhausted");
                }
                EngineRunResult llm = modelGateway.call(withSuppress(ctx, Phase.ACT, goal), Phase.ACT, goal);
                ExecutionResult llmResult = toExecutionResult(llm, rc, iter);
                loopCtx.result(llmResult);
                runtime.afterLlm(loopCtx);

                if (llm == null || llm.error() != null) {
                    return EngineRunResult.error(ref.sessionId(), Phase.ACT,
                            llm == null ? "引擎无返回结果" : llm.error());
                }
                if ("ask".equals(llm.status())) {
                    return llm;
                }

                // ── 第 2 步：执行工具 / 子代理 ──
                executeOrchestrationStep(rc, llmResult);

                // ── 第 3 步：回填观察 ──
                String output = llmResult.output() == null ? "" : llmResult.output();
                observations.append("\n- 第 ").append(iter).append(" 轮：").append(brief(output));
                stateManager.session(ref).appendMessage(
                        dev.langchain4j.data.message.UserMessage.from("【观察】本轮结果：" + brief(output)));

                // 结束与否、下一轮目标为何，全部由运行时决策（验证/守卫/记忆/安全阀已下沉）
                LoopDecision decision = controller.decide(iter, goal, output, rc, runtime);
                if (decision.terminate()) {
                    return EngineRunResult.of(ref.sessionId(), Phase.ACT,
                            decision.isAsk() ? decision.text() : blankTo(output, decision.text()),
                            session.global().usedTokens(), llm.model(), decision.status());
                }
                goal = decision.nextGoal();
            }

            return EngineRunResult.of(ref.sessionId(), Phase.ACT,
                    "已达最大迭代次数，未能完全达成目标。已完成的执行情况：\n" + observations,
                    session.global().usedTokens(), null, "max_iterations");
        } finally {
            sessions.close(session, runtime);
        }
    }

    /** 编排级工具执行：策略决定何时拆解、何时启动子代理；具体执行交给运行时工具（含中间件链）。 */
    private void executeOrchestrationStep(RuntimeContext rc, ExecutionResult llmResult) {
        StrategyPlugin strategy = runtime.selectStrategy(rc);
        if (strategy == null) {
            return;
        }
        if (strategy.shouldDecompose(rc, llmResult.output())
                && runtime.tools().find("decompose").isPresent()) {
            runtime.invokeTool("decompose", Map.of("goal", nullSafe(rc.goal())), rc);
        }
        if (strategy.shouldSpawnSubAgents(rc, llmResult.output())
                && runtime.tools().find("spawn_sub_agent").isPresent()) {
            runtime.invokeTool("spawn_sub_agent", Map.of("task", nullSafe(rc.goal())), rc);
        }
    }

    private ExecutionResult toExecutionResult(EngineRunResult llm, RuntimeContext rc, int iter) {
        if (llm == null) {
            return ExecutionResult.failure("引擎无返回结果");
        }
        if (llm.error() != null) {
            return ExecutionResult.failure(llm.error());
        }
        return ExecutionResult.success(llm.finalText(), List.of(),
                new ExecutionMetrics(Math.max(0, llm.tokenUsed()), 0L, 0, iter), rc.trace());
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

    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? (fallback == null ? "" : fallback) : value;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
