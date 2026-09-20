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
 */
public record RuntimeSession(BudgetScope global, RuntimeContext context, MiddlewareContext loopContext) {
}
