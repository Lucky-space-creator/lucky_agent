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
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.planactask.ActScheduler;
import com.lucky.agent.core.planactask.AskSuspender;
import com.lucky.agent.core.planactask.PlanGenerator;
import com.lucky.agent.core.planactask.Replanner;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.core.subagent.TaskProgressTracker;
import com.lucky.agent.core.subagent.TaskScheduler;
import com.lucky.agent.core.verify.VerificationChain;
import com.lucky.agent.core.verify.VerificationVerdict;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排主回环集成测试（核心链路 P0-1 / P1-2 / P2-1 / P2-2 / P2-3 行为验收）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>安全阀触发：迭代次数耗尽 → 强制结束返回 reason=max_iterations；</li>
 *   <li>重试超限提前终止：子任务多次失败 → 返回 reason=aborted；</li>
 *   <li>ASK 中断：非 FULL 权限下高危操作 → 挂起并中断（status=ask）；</li>
 *   <li>独立预算：每次 run 新建 RunBudget 不跨 submit 累积（由属性配置保证语义）。</li>
 * </ul>
 */
class OrchestratorTest {

    private static final String SESSION = "s-orch";

    private ConversationCtx ctx(PermissionLevel level) {
        return ConversationCtx.builder()
                .sessionRef(new SessionRef(SESSION, "u-1", "ws-1"))
                .phase(Phase.ACT)
                .permissionLevel(level)
                .goal("完成任务")
                .extra(Map.of())
                .build();
    }

    private CoreProperties props(int maxIter, int maxRetry) {
        return new CoreProperties(12, 30, 30, -1, false, 4, 300,
                maxIter, maxRetry, "reactor", Boolean.TRUE, 120, 500, 0.3);
    }

    private Orchestrator orchestrator(Engine engine, VerificationChain chain,
                                     CoreProperties props, AskSuspender suspender) {
        ConversationStateManager stateManager = mock(ConversationStateManager.class);
        ConversationStateManager.SessionState state = mock(ConversationStateManager.SessionState.class);
        when(state.messages()).thenReturn(new java.util.concurrent.CopyOnWriteArrayList<>());
        when(stateManager.session(any())).thenReturn(state);
        return new Orchestrator(engine, stateManager,
                new PlanGenerator(), new com.lucky.agent.core.models.PlanValidator(),
                new Replanner(engine), new TaskProgressTracker(), chain,
                mock(LoopMemoryManager.class), props, suspender,
                new StepLimitGuard(props.planMaxSteps(), props.actMaxSteps()),
                new EarlyStopPolicy(props.earlyStopConfidenceThreshold()),
                new ActScheduler(engine),
                mock(TaskScheduler.class));
    }

    /** 构造一个含单个步骤的计划（模型判定需拆分）。 */
    private Plan planWithSteps() {
        Plan.PlanStep step = new Plan.PlanStep(1, "file", "写文件", "app.txt", true, null);
        return new Plan("完成任务", List.of(step), false);
    }

    // ---------- 1. 安全阀触发：迭代耗尽 ----------
    @Test
    void testSafetyValve_MaxIterations() {
        Engine engine = mock(Engine.class);
        // PLAN 持续产出有效计划（含步骤），使主回环反复进入拆分分支
        when(engine.run(any(), any(), anyString())).thenReturn(Mono.just(
                EngineRunResult.of(SESSION, Phase.PLAN, planJson(), 0, "m", "success")));
        VerificationChain chain = mock(VerificationChain.class);
        // 验证始终判定未达成，但每轮结论不同（模拟每轮有进展），避免触发「重复结论」循环守卫，
        // 从而验证纯粹的迭代次数安全阀（max_iterations）。
        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        when(chain.verify(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> new VerificationVerdict(false,
                        "进度-" + round.incrementAndGet(), "继续", List.of()));
        CoreProperties props = props(2, 0);
        AskSuspender suspender = mock(AskSuspender.class);
        Orchestrator orch = orchestrator(engine, chain, props, suspender);

        EngineRunResult r = orch.run(new SessionRef(SESSION, "u-1", "ws-1"), ctx(PermissionLevel.FULL),
                mock(AgentEventPublisher.class));

        assertEquals("max_iterations", r.status());
    }

    // ---------- 2. 重试超限提前终止 ----------
    @Test
    void testSubTaskRetryExhausted_Aborted() {
        Engine engine = mock(Engine.class);
        // PLAN 产出含步骤的计划
        when(engine.run(any(), any(), anyString())).thenAnswer(inv -> {
            Phase phase = inv.getArgument(1);
            if (phase == Phase.PLAN) {
                return Mono.just(EngineRunResult.of(SESSION, Phase.PLAN, planJson(), 0, "m", "success"));
            }
            // ACT 执行子任务：始终报错（触发重试直至超限）
            return Mono.just(EngineRunResult.error(SESSION, Phase.ACT, "执行失败"));
        });
        VerificationChain chain = mock(VerificationChain.class);
        CoreProperties props = props(5, 1); // 仅 1 次重试
        AskSuspender suspender = mock(AskSuspender.class);
        Orchestrator orch = orchestrator(engine, chain, props, suspender);

        EngineRunResult r = orch.run(new SessionRef(SESSION, "u-1", "ws-1"), ctx(PermissionLevel.FULL),
                mock(AgentEventPublisher.class));

        assertEquals("aborted", r.status());
        assertTrue(r.finalText().contains("提前终止"));
    }

    // ---------- 3. ASK 中断（非 FULL 权限） ----------
    @Test
    void testAskInterrupted_NonFullPermission() {
        Engine engine = mock(Engine.class);
        when(engine.run(any(), any(), anyString())).thenAnswer(inv -> {
            Phase phase = inv.getArgument(1);
            if (phase == Phase.PLAN) {
                return Mono.just(EngineRunResult.of(SESSION, Phase.PLAN, planJson(), 0, "m", "success"));
            }
            // ACT 触发高危确认（status=ask）
            return Mono.just(EngineRunResult.of(SESSION, Phase.ACT, null, 0, "m", "ask"));
        });
        VerificationChain chain = mock(VerificationChain.class);
        CoreProperties props = props(5, 2);
        AskSuspender suspender = mock(AskSuspender.class);
        Orchestrator orch = orchestrator(engine, chain, props, suspender);

        EngineRunResult r = orch.run(new SessionRef(SESSION, "u-1", "ws-1"), ctx(PermissionLevel.READ_ONLY),
                mock(AgentEventPublisher.class));

        assertEquals("ask", r.status());
        // P0-1：应触发挂起持久化
        verify(suspender, times(1)).suspend(any(), anyString(), anyString());
    }

    // ---------- 4. 独立预算：run 内部新建 RunBudget（语义由 CoreProperties 驱动） ----------
    @Test
    void testIndependentBudget_PerRun() {
        Engine engine = mock(Engine.class);
        // PLAN 返回无步骤计划 → 模型判定无需拆分 → 单次 ACT 直接执行
        when(engine.run(any(), any(), anyString())).thenAnswer(inv -> {
            Phase phase = inv.getArgument(1);
            if (phase == Phase.PLAN) {
                // 非结构化输出 → 模型判定无需拆分（analyze 返回 null）
                return Mono.just(EngineRunResult.of(SESSION, Phase.PLAN,
                        "直接执行即可，无需拆分。", 0, "m", "success"));
            }
            return Mono.just(EngineRunResult.of(SESSION, Phase.ACT, "完成", 0, "m", "success"));
        });
        VerificationChain chain = mock(VerificationChain.class);
        // 单步也走轻量验证（P2-2）：判定达成
        when(chain.verify(any(), any(), anyString(), anyString(), any()))
                .thenReturn(new VerificationVerdict(true, "完成", "x", List.of()));
        CoreProperties props = props(5, 2);
        AskSuspender suspender = mock(AskSuspender.class);
        Orchestrator orch = orchestrator(engine, chain, props, suspender);

        EngineRunResult r = orch.run(new SessionRef(SESSION, "u-1", "ws-1"), ctx(PermissionLevel.FULL),
                mock(AgentEventPublisher.class));

        assertEquals("success", r.status());
        assertEquals("完成", r.finalText());
    }

    private String planJson() {
        return "{\"goal\":\"完成任务\",\"steps\":[{\"id\":1,\"type\":\"file\",\"desc\":\"写文件\","
                + "\"target\":\"app.txt\",\"safe\":true}],\"canAutoExecute\":false}";
    }

    /** 含子代理声明的 PLAN JSON（无 steps，纯子代理决策）。 */
    private String planJsonWithSubagent() {
        return "{\"goal\":\"完成高复杂度任务\",\"steps\":[],\"canAutoExecute\":false,"
                + "\"subagents\":[{\"id\":\"sa-1\",\"name\":\"评审A\",\"task\":\"独立评审模块A\"}]}";
    }

    private CoreProperties propsEnabled(int maxIter, int maxRetry) {
        return new CoreProperties(12, 30, 30, -1, true, 4, 300,
                maxIter, maxRetry, "reactor", Boolean.TRUE, 120, 500, 0.3);
    }

    // ---------- 5. subagent-enabled=true 且 PLAN 声明 subagents → 路由到 TaskScheduler ----------
    @Test
    void testSubAgentRoutedWhenEnabled() {
        Engine engine = mock(Engine.class);
        when(engine.run(any(), any(), anyString())).thenAnswer(inv -> {
            Phase phase = inv.getArgument(1);
            if (phase == Phase.PLAN) {
                return Mono.just(EngineRunResult.of(SESSION, Phase.PLAN,
                        planJsonWithSubagent(), 0, "m", "success"));
            }
            return Mono.just(EngineRunResult.of(SESSION, Phase.ACT, "完成", 0, "m", "success"));
        });
        VerificationChain chain = mock(VerificationChain.class);
        when(chain.verify(any(), any(), anyString(), anyString(), any()))
                .thenReturn(new VerificationVerdict(true, "完成", "x", List.of()));
        CoreProperties props = propsEnabled(5, 2);
        AskSuspender suspender = mock(AskSuspender.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        // 子代理调度返回一条摘要结果
        when(scheduler.scheduleSerial(any(), any(), anyString(), anyString()))
                .thenReturn(Flux.just(new com.lucky.agent.core.subagent.SubAgentResult(
                        "sa-1", "模块A已独立评审通过", true)));
        Orchestrator orch = orchestrator(engine, chain, props, suspender, scheduler);

        EngineRunResult r = orch.run(new SessionRef(SESSION, "u-1", "ws-1"), ctx(PermissionLevel.FULL),
                mock(AgentEventPublisher.class));

        assertEquals("success", r.status());
        // 子代理调度器应被调用
        verify(scheduler, times(1)).scheduleSerial(any(), any(), anyString(), anyString());
    }

    /** 装配支持注入 TaskScheduler 的编排器。 */
    private Orchestrator orchestrator(Engine engine, VerificationChain chain,
                                     CoreProperties props, AskSuspender suspender, TaskScheduler scheduler) {
        ConversationStateManager stateManager = mock(ConversationStateManager.class);
        ConversationStateManager.SessionState state = mock(ConversationStateManager.SessionState.class);
        when(state.messages()).thenReturn(new java.util.concurrent.CopyOnWriteArrayList<>());
        when(stateManager.session(any())).thenReturn(state);
        return new Orchestrator(engine, stateManager,
                new PlanGenerator(), new com.lucky.agent.core.models.PlanValidator(),
                new Replanner(engine), new TaskProgressTracker(), chain,
                mock(LoopMemoryManager.class), props, suspender,
                new StepLimitGuard(props.planMaxSteps(), props.actMaxSteps()),
                new EarlyStopPolicy(props.earlyStopConfidenceThreshold()),
                new ActScheduler(engine),
                scheduler);
    }
}
