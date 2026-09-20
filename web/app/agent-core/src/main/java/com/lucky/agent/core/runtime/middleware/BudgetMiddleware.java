package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionResult;

import java.util.Optional;

/**
 * 预算中间件：把原主循环里硬编码的「安全阀（N）」下沉为可插拔横切能力。
 *
 * <ul>
 *   <li>{@code beforeLlm}：全局预算预检，耗尽即短路本轮（强制结束并总结）；</li>
 *   <li>{@code afterLlm}：累计 token 消耗（自底向上累计到全局）；</li>
 *   <li>{@code beforeTool}：工具执行前同样预检，避免预算耗尽后仍继续产生副作用。</li>
 * </ul>
 */
public class BudgetMiddleware implements Middleware {

    @Override
    public int order() {
        return MiddlewareOrder.BUDGET;
    }

    @Override
    public void beforeLlm(MiddlewareContext ctx) {
        Optional<String> reason = ctx.runtime().budget().globalExhaustedReason();
        reason.ifPresent(r -> {
            ctx.result(ExecutionResult.budgetExhausted(r));
            ctx.block("预算耗尽: " + r);
        });
    }

    @Override
    public void afterLlm(MiddlewareContext ctx) {
        ExecutionResult result = ctx.result();
        if (result != null && result.metrics() != null) {
            ctx.runtime().budget().record(null, result.metrics().tokenUsed());
        }
    }

    @Override
    public void beforeTool(MiddlewareContext ctx) {
        Optional<String> reason = ctx.runtime().budget().globalExhaustedReason();
        reason.ifPresent(r -> {
            ctx.result(ExecutionResult.budgetExhausted(r));
            ctx.block("预算耗尽（工具调用前）: " + r);
        });
    }
}
