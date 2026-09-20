package com.lucky.agent.core.runtime;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.runtime.budget.BudgetLevel;
import com.lucky.agent.core.runtime.budget.BudgetManager;
import com.lucky.agent.core.runtime.budget.BudgetScope;
import com.lucky.agent.core.runtime.contract.ExecutionMetrics;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.ExecutionStatus;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.contract.TraceContext;
import com.lucky.agent.core.runtime.loop.AgentRuntime;
import com.lucky.agent.core.runtime.loop.LoopController;
import com.lucky.agent.core.runtime.loop.LoopDecision;
import com.lucky.agent.core.runtime.loop.SubAgentPool;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import com.lucky.agent.core.runtime.middleware.CompactionMiddleware;
import com.lucky.agent.core.runtime.middleware.MiddlewareChain;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.runtime.middleware.PermissionMiddleware;
import com.lucky.agent.core.runtime.middleware.RetryMiddleware;
import com.lucky.agent.core.runtime.plan.HeuristicStepDecomposer;
import com.lucky.agent.core.runtime.tool.DecomposeTool;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import com.lucky.agent.core.runtime.verify.ChainVerifierAdapter;
import com.lucky.agent.core.runtime.verify.FileArtifactVerifier;
import com.lucky.agent.core.runtime.verify.VerificationOutcome;
import com.lucky.agent.core.runtime.verify.VerificationRequest;
import com.lucky.agent.core.runtime.verify.Verifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 运行时组件测试：权限/重试/压缩中间件、拆解与子代理工具、循环控制器、验证桥接。 */
class RuntimeComponentsTest {

    private final RecordingMemory memory = new RecordingMemory();
    private final SubAgentPool pool = new SubAgentPool(2, 2);

    @AfterEach
    void tearDown() {
        pool.close();
    }

    // ==================== 基础设施 ====================

    private RuntimeContext rc(PermissionLevel level) {
        SessionRef ref = SessionRef.of("s-1", "u-1", "w-1");
        ConversationCtx conversation = ConversationCtx.builder()
                .sessionRef(ref)
                .goal("实现一个功能")
                .permissionLevel(level)
                .build();
        return new RuntimeContext(ref, conversation, null, TraceContext.root(),
                new BudgetManager(new BudgetScope(BudgetLevel.GLOBAL, -1L, 0L, 3)));
    }

    private RuntimeContext rc() {
        return rc(PermissionLevel.MODIFY);
    }

    private AgentRuntime runtimeWith(Verifier verifier) {
        return new AgentRuntime(List.of(), new ToolRegistry(List.of()), List.of(),
                verifier, memory, pool);
    }

    /** 记录调用次数、可指定前 N 次失败的工具。 */
    private static final class CountingTool implements RuntimeTool {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failTimes;
        private final PermissionLevel required;
        private final Supplier<ExecutionResult> forced;
        private final boolean retryable;

        CountingTool(int failTimes, PermissionLevel required) {
            this(failTimes, required, null, true);
        }

        CountingTool(int failTimes, PermissionLevel required, Supplier<ExecutionResult> forced, boolean retryable) {
            this.failTimes = failTimes;
            this.required = required;
            this.forced = forced;
            this.retryable = retryable;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public String description() {
            return "计数工具";
        }

        @Override
        public PermissionLevel requiredPermission() {
            return required;
        }

        @Override
        public boolean retryable() {
            return retryable;
        }

        @Override
        public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
            int n = calls.incrementAndGet();
            if (forced != null) {
                return forced.get();
            }
            return n <= failTimes
                    ? ExecutionResult.failure("第 " + n + " 次失败")
                    : ExecutionResult.success("ok-" + n);
        }
    }

    // ==================== 权限中间件 ====================

    @Test
    void permissionMiddlewareShouldAskInsteadOfFailingWhenLevelInsufficient() {
        CountingTool tool = new CountingTool(0, PermissionLevel.FULL);
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        MiddlewareChain chain = new MiddlewareChain(List.of(new PermissionMiddleware(registry)));

        MiddlewareContext ctx = new MiddlewareContext(rc(PermissionLevel.MODIFY));
        ctx.toolCall(ToolCall.of("counting", Map.of()));
        chain.beforeTool(ctx);

        assertThat(ctx.blocked()).isTrue();
        assertThat(ctx.result()).isNotNull();
        assertThat(ctx.result().status()).isEqualTo(ExecutionStatus.ASK); // 越权转 ASK，而非静默失败
        assertThat(tool.calls.get()).isZero();                           // 工具未被真正执行
    }

    @Test
    void permissionMiddlewareShouldAllowWhenLevelSufficient() {
        CountingTool tool = new CountingTool(0, PermissionLevel.MODIFY);
        MiddlewareChain chain = new MiddlewareChain(List.of(new PermissionMiddleware(new ToolRegistry(List.of(tool)))));

        MiddlewareContext ctx = new MiddlewareContext(rc(PermissionLevel.FULL));
        ctx.toolCall(ToolCall.of("counting", Map.of()));
        chain.beforeTool(ctx);

        assertThat(ctx.blocked()).isFalse();
    }

    // ==================== 重试中间件 ====================

    @Test
    void retryMiddlewareShouldRetryFailedToolThenSucceed() {
        CountingTool tool = new CountingTool(2, PermissionLevel.READ_ONLY);
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        MiddlewareChain chain = new MiddlewareChain(List.of(new RetryMiddleware(registry, 3, 0L)));

        MiddlewareContext ctx = new MiddlewareContext(rc());
        ctx.toolCall(ToolCall.of("counting", Map.of()));
        ExecutionResult result = chain.aroundTool(ctx, () -> registry.invoke(ctx.toolCall(), ctx.runtime()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(tool.calls.get()).isEqualTo(3);
        assertThat(result.metrics().retries()).isEqualTo(2); // 重试次数记入指标
    }

    @Test
    void retryMiddlewareShouldNotRetryAskNorNonRetryableTool() {
        CountingTool asking = new CountingTool(0, PermissionLevel.READ_ONLY,
                () -> ExecutionResult.ask("需用户确认"), true);
        ToolRegistry askRegistry = new ToolRegistry(List.of(asking));
        MiddlewareChain askChain = new MiddlewareChain(List.of(new RetryMiddleware(askRegistry, 3, 0L)));
        MiddlewareContext askCtx = new MiddlewareContext(rc());
        askCtx.toolCall(ToolCall.of("counting", Map.of()));
        ExecutionResult askResult = askChain.aroundTool(askCtx, () -> askRegistry.invoke(askCtx.toolCall(), askCtx.runtime()));
        assertThat(askResult.status()).isEqualTo(ExecutionStatus.ASK);
        assertThat(asking.calls.get()).isEqualTo(1); // ASK 不重试

        CountingTool nonRetryable = new CountingTool(5, PermissionLevel.READ_ONLY, null, false);
        ToolRegistry nrRegistry = new ToolRegistry(List.of(nonRetryable));
        MiddlewareChain nrChain = new MiddlewareChain(List.of(new RetryMiddleware(nrRegistry, 3, 0L)));
        MiddlewareContext nrCtx = new MiddlewareContext(rc());
        nrCtx.toolCall(ToolCall.of("counting", Map.of()));
        nrChain.aroundTool(nrCtx, () -> nrRegistry.invoke(nrCtx.toolCall(), nrCtx.runtime()));
        assertThat(nonRetryable.calls.get()).isEqualTo(1); // 非幂等工具不重试
    }

    // ==================== 压缩中间件 ====================

    @Test
    void compactionMiddlewareShouldCompactOnlyAfterThreshold() {
        CompactionMiddleware middleware = new CompactionMiddleware(memory, 100L);
        RuntimeContext context = rc();
        MiddlewareContext ctx = new MiddlewareContext(context);

        ctx.result(withTokens(60));
        middleware.afterLlm(ctx);
        assertThat(memory.compactions).isEmpty(); // 未达阈值不压缩

        ctx.result(withTokens(60));
        middleware.afterLlm(ctx);
        assertThat(memory.compactions).hasSize(1);  // 跨阈值压缩
        assertThat(memory.records).isEmpty();       // 压缩不等于沉淀
    }

    private ExecutionResult withTokens(long tokens) {
        return ExecutionResult.success("输出", List.of(),
                new ExecutionMetrics(tokens, 0L, 0, 0), null);
    }

    // ==================== 拆解工具 ====================

    @Test
    void decomposeToolShouldSplitNumberedGoal() {
        DecomposeTool tool = new DecomposeTool(new HeuristicStepDecomposer());
        RuntimeContext context = rc();

        ExecutionResult result = tool.invoke(
                ToolCall.of("decompose", Map.of("goal", "1. 读取配置\n2. 修改参数\n3. 跑测试")), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.metadata().get("stepCount")).isEqualTo(3);
        assertThat(DecomposeTool.steps(context)).hasSize(3);
        assertThat(result.output()).contains("1. 读取配置");
    }

    @Test
    void decomposeToolShouldFallBackToDefaultPlan() {
        DecomposeTool tool = new DecomposeTool(new HeuristicStepDecomposer());
        RuntimeContext context = rc();

        ExecutionResult result = tool.invoke(ToolCall.of("decompose", Map.of("goal", "修复登录缺陷")), context);

        assertThat(result.metadata().get("stepCount")).isEqualTo(3); // 兜底三段式
    }

    // ==================== 循环控制器 ====================

    @Test
    void loopControllerShouldRecordLongTermMemoryOnlyOnSuccess() {
        LoopController controller = new LoopController(memory, 3, 0.3);
        RuntimeContext context = rc();
        AgentRuntime runtime = runtimeWith(fixedOutcome(
                VerificationOutcome.done("已完成", List.of("test -> exit=0"))));

        LoopDecision decision = controller.decide(1, "目标", "完成输出", context, runtime);

        assertThat(decision.terminate()).isTrue();
        assertThat(decision.status()).isEqualTo("success");
        assertThat(memory.records).hasSize(1);    // 成功才沉淀
        assertThat(memory.compactions).isEmpty();
    }

    @Test
    void loopControllerShouldOnlyCompactOnFailure() {
        LoopController controller = new LoopController(memory, 3, 0.3);
        RuntimeContext context = rc();
        AgentRuntime runtime = runtimeWith(fixedOutcome(
                new VerificationOutcome(false, 0.5, false, List.of(), "仍有未完成项", "继续修 A")));

        LoopDecision decision = controller.decide(1, "目标", "部分输出", context, runtime);

        assertThat(decision.terminate()).isFalse();
        assertThat(decision.nextGoal()).isEqualTo("继续修 A");
        assertThat(memory.compactions).hasSize(1);  // 失败只压缩
        assertThat(memory.records).isEmpty();       // 不沉淀长期记忆
        assertThat(memory.compactions.get(0).get(MemoryPort.Fields.OPEN_QUESTIONS)).isEqualTo("继续修 A");
    }

    @Test
    void loopControllerShouldDetectStuckAndAskOnLowConfidence() {
        // 卡死守卫：连续两轮判定摘要一致 → 提前终止
        LoopController stuckController = new LoopController(memory, 5, 0.3);
        RuntimeContext stuckCtx = rc();
        AgentRuntime stuckRuntime = runtimeWith(fixedOutcome(
                new VerificationOutcome(false, 0.9, true, List.of("cmd -> exit=1"), "同一结论", "再试")));
        assertThat(stuckController.decide(1, "目标", "输出", stuckCtx, stuckRuntime).terminate()).isFalse();
        LoopDecision stuck = stuckController.decide(2, "目标", "输出", stuckCtx, stuckRuntime);
        assertThat(stuck.terminate()).isTrue();
        assertThat(stuck.status()).isEqualTo("stuck");

        // 极弱判定（无客观信号 + 置信度低于阈值）→ 转 ASK，避免盲跑
        LoopController weakController = new LoopController(new RecordingMemory(), 5, 0.3);
        AgentRuntime weakRuntime = runtimeWith(fixedOutcome(
                VerificationOutcome.subjective(false, "无法判断", 0.2)));
        LoopDecision ask = weakController.decide(1, "目标", "输出", rc(), weakRuntime);
        assertThat(ask.terminate()).isTrue();
        assertThat(ask.isAsk()).isTrue();
    }

    // ==================== 验证桥接与轻量验证器 ====================

    @Test
    void chainVerifierAdapterShouldAbstainWithoutRuntimeContext() {
        assertThat(new ChainVerifierAdapter(null).verify(VerificationRequest.of("g", "o"))).isNull();
        assertThat(new ChainVerifierAdapter(null).verify(VerificationRequest.of("g", "o", rc()))).isNull();
    }

    @Test
    void fileArtifactVerifierShouldBeObjectiveAndAbstainWhenNoEvidence() throws Exception {
        FileArtifactVerifier verifier = new FileArtifactVerifier();
        assertThat(verifier.objective()).isTrue();
        assertThat(verifier.verify(VerificationRequest.of("g", "o"))).isNull(); // 无产物声明 → 不表态

        Path existing = Files.createTempFile("artifact", ".txt");
        try {
            VerificationOutcome ok = verifier.verify(new VerificationRequest(
                    "g", "o", List.of(), Map.of("files", List.of(existing.toString())), null, null));
            assertThat(ok).isNotNull();
            assertThat(ok.done()).isTrue();

            VerificationOutcome missing = verifier.verify(new VerificationRequest(
                    "g", "o", List.of(), Map.of("files", List.of("no-such-file-xyz.bin")), null, null));
            assertThat(missing).isNotNull();
            assertThat(missing.done()).isFalse();
            assertThat(missing.objective()).isTrue();
        } finally {
            Files.deleteIfExists(existing);
        }
    }

    // ==================== 测试替身 ====================

    private Verifier fixedOutcome(VerificationOutcome outcome) {
        return new Verifier() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public boolean objective() {
                return outcome.objective();
            }

            @Override
            public VerificationOutcome verify(VerificationRequest request) {
                return outcome;
            }
        };
    }

    /** 记录沉淀/压缩调用的记忆端口替身。 */
    private static final class RecordingMemory implements MemoryPort {
        private final List<String> records = new ArrayList<>();
        private final List<Map<String, Object>> compactions = new ArrayList<>();

        @Override
        public String recall(String sessionId, String query) {
            return "";
        }

        @Override
        public void recordLongTerm(String sessionId, String content, Map<String, Object> structured) {
            records.add(content);
        }

        @Override
        public void compact(String sessionId, Map<String, Object> structuredFields) {
            compactions.add(structuredFields);
        }
    }
}
