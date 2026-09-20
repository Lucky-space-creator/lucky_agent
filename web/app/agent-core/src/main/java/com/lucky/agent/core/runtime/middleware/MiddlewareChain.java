package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionResult;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 中间件链：按 {@link Middleware#order()} 排序后依序执行各钩子。
 *
 * <p>通知型钩子（before/after）：任一中间件 {@code block} 后，剩余中间件不再执行（deny 优先）。</p>
 * <p>环绕型钩子（{@link #aroundTool}）：逆序折叠包裹，使 order 最小者位于最外层，
 * 于是「权限/预算预检 → 重试 → 日志 → 真实工具调用」的嵌套顺序与 order 直觉一致。</p>
 */
public class MiddlewareChain {

    private final List<Middleware> chain;

    public MiddlewareChain(List<Middleware> middlewares) {
        this.chain = (middlewares == null ? List.<Middleware>of() : middlewares).stream()
                .sorted(Comparator.comparingInt(Middleware::order))
                .toList();
    }

    public List<Middleware> middlewares() {
        return chain;
    }

    public void onLoopStart(MiddlewareContext ctx) {
        run(ctx, m -> m.onLoopStart(ctx));
    }

    public void beforeLlm(MiddlewareContext ctx) {
        run(ctx, m -> m.beforeLlm(ctx));
    }

    public void afterLlm(MiddlewareContext ctx) {
        run(ctx, m -> m.afterLlm(ctx));
    }

    public void beforeTool(MiddlewareContext ctx) {
        run(ctx, m -> m.beforeTool(ctx));
    }

    public void afterTool(MiddlewareContext ctx) {
        run(ctx, m -> m.afterTool(ctx));
    }

    public void onLoopEnd(MiddlewareContext ctx) {
        run(ctx, m -> m.onLoopEnd(ctx));
    }

    /**
     * 环绕执行一次工具调用（承载重试等织入能力）。
     *
     * <p>组合方式为逆序折叠：{@code m0 -> m1 -> m2 -> terminal}，
     * 即 order 最小（排序最前）者处于最外层，最先决定是否放行、最后收到结果。</p>
     *
     * @param ctx      中间件上下文
     * @param terminal 最内层真实调用（通常是工具注册表分发）
     * @return 最终结果
     */
    public ExecutionResult aroundTool(MiddlewareContext ctx, Supplier<ExecutionResult> terminal) {
        Supplier<ExecutionResult> composed = terminal;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware middleware = chain.get(i);
            Supplier<ExecutionResult> inner = composed;
            composed = () -> {
                if (ctx.blocked()) {
                    return blockedResult(ctx);
                }
                return middleware.aroundTool(ctx, inner);
            };
        }
        return composed.get();
    }

    private ExecutionResult blockedResult(MiddlewareContext ctx) {
        // 中间件可用 ctx.result(...) 携带语义化结果（如 ASK），否则退化为 FAILED
        ExecutionResult pending = ctx.result();
        return pending != null ? pending
                : ExecutionResult.failure("工具被中间件阻断: " + ctx.blockReason());
    }

    private void run(MiddlewareContext ctx, Consumer<Middleware> invocation) {
        for (Middleware m : chain) {
            if (ctx.blocked()) {
                return;
            }
            invocation.accept(m);
        }
    }
}
