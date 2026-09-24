package com.lucky.agent.core.runtime;

import com.lucky.agent.core.runtime.budget.BudgetScope;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;

/**
 * 一次编排的运行时会话：把「预算 + 运行时上下文 + 中间件上下文」三件套绑在一起。
 *
 * @param global      全局预算作用域
 * @param context     运行时上下文（贯穿工具/中间件/策略/验证器）
 * @param loopContext 中间件上下文（钩子间传递的可变载体）
 * @param turnLimit   本次运行的回合上限（由 {@link RuntimeSessionFactory} 在构造预算时一并解析并绑定）。
 *                    显式随会话携带，是为了让主环的「回合安全阀」与「预算作用域」读取<b>同一个已解析值</b>；
 *                    此前 langgraph 主环的回合安全阀直接读全局 {@code CoreProperties}，与预算作用域形成双来源，
 *                    一旦引入 per-run 覆盖即静默分叉（见 {@code RunOverrides}）。
 */
public record RuntimeSession(BudgetScope global, RuntimeContext context, MiddlewareContext loopContext,
                             int turnLimit) {
}
