package com.lucky.agent.core.runtime;

import com.lucky.agent.core.runtime.budget.BudgetLevel;
import com.lucky.agent.core.runtime.budget.BudgetManager;
import com.lucky.agent.core.runtime.budget.BudgetScope;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.contract.TraceContext;
import com.lucky.agent.core.runtime.middleware.Middleware;
import com.lucky.agent.core.runtime.middleware.MiddlewareChain;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import com.lucky.agent.core.runtime.verify.CompositeVerifier;
import com.lucky.agent.core.runtime.verify.VerificationOutcome;
import com.lucky.agent.core.runtime.verify.VerificationRequest;
import com.lucky.agent.core.runtime.verify.Verifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 运行时核心件测试：分层预算、中间件链、工具注册、客观优先验证链。 */
class RuntimeCoreTest {

    private RuntimeContext ctx() {
        return new RuntimeContext(null, null, null, TraceContext.root(), new BudgetManager(new BudgetScope(BudgetLevel.GLOBAL, 100, 0, 3)));
    }

    @Test
    void budgetShouldExhaustAndPropagateToGlobal() {
        BudgetScope global = new BudgetScope(BudgetLevel.GLOBAL, 100, 0, 3);
        BudgetManager manager = new BudgetManager(global);
        BudgetScope step = manager.step("s1", 60, 0, 2);

        manager.record(step, 50);
        assertThat(global.usedTokens()).isEqualTo(50);
        assertThat(step.tokensExhausted()).isFalse();

        manager.record(step, 20);
        assertThat(step.tokensExhausted()).isTrue();
        assertThat(manager.globalExhaustedReason()).isEmpty(); // 全局 70 < 100

        manager.record(null, 40);
        assertThat(manager.globalExhaustedReason()).isPresent();
        assertThat(manager.canProceed()).isFalse();
    }

    @Test
    void middlewareShouldRunInOrderAndBlockChain() {
        List<String> trace = new ArrayList<>();
        Middleware first = new Middleware() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public void beforeLlm(MiddlewareContext c) {
                trace.add("first");
            }
        };
        Middleware blocking = new Middleware() {
            @Override
            public int order() {
                return 2;
            }

            @Override
            public void beforeLlm(MiddlewareContext c) {
                trace.add("blocking");
                c.block("denied");
            }
        };
        Middleware last = new Middleware() {
            @Override
            public int order() {
                return 3;
            }

            @Override
            public void beforeLlm(MiddlewareContext c) {
                trace.add("last");
            }
        };

        MiddlewareChain chain = new MiddlewareChain(List.of(last, blocking, first));
        MiddlewareContext mctx = new MiddlewareContext(ctx());
        chain.beforeLlm(mctx);

        assertThat(trace).containsExactly("first", "blocking"); // deny 优先，last 被短路
        assertThat(mctx.blocked()).isTrue();
    }

    @Test
    void toolRegistryShouldDispatchByName() {
        RuntimeTool echo = new RuntimeTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public ExecutionResult invoke(ToolCall call, RuntimeContext ignored) {
                return ExecutionResult.success("echo:" + call.arg("msg"));
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echo));

        ExecutionResult ok = registry.invoke(ToolCall.of("echo", Map.of("msg", "hi")), ctx());
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.output()).isEqualTo("echo:hi");

        assertThat(registry.invoke(ToolCall.of("missing", Map.of()), ctx()).isSuccess()).isFalse();
        assertThat(registry.specs()).hasSize(1);
    }

    @Test
    void compositeVerifierShouldPreferObjective() {
        Verifier abstain = new Verifier() {
            @Override
            public String name() {
                return "abstain";
            }

            @Override
            public boolean objective() {
                return false;
            }

            @Override
            public VerificationOutcome verify(VerificationRequest request) {
                return null; // 不表态
            }
        };
        Verifier objectiveFail = new Verifier() {
            @Override
            public String name() {
                return "objective";
            }

            @Override
            public boolean objective() {
                return true;
            }

            @Override
            public VerificationOutcome verify(VerificationRequest request) {
                return new VerificationOutcome(false, 1.0, true, List.of("cmd -> exit=1"),
                        "客观校验未通过", "retry");
            }
        };

        // 传入顺序为 [主观, 客观]，CompositeVerifier 应把客观验证器排到前面
        CompositeVerifier composite = new CompositeVerifier(List.of(abstain, objectiveFail));
        VerificationOutcome outcome = composite.verify(VerificationRequest.of("g", "o"));

        assertThat(outcome.done()).isFalse();
        assertThat(outcome.objective()).isTrue();
        assertThat(outcome.confidence()).isEqualTo(1.0);
        assertThat(outcome.continueGoal()).isEqualTo("retry");
    }
}
