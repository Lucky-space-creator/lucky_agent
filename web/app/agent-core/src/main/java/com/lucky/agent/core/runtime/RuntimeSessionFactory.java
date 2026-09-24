package com.lucky.agent.core.runtime;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.runtime.budget.BudgetLevel;
import com.lucky.agent.core.runtime.budget.BudgetManager;
import com.lucky.agent.core.runtime.budget.BudgetScope;
import com.lucky.agent.core.runtime.budget.RunOverrides;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.TraceContext;
import com.lucky.agent.core.runtime.loop.AgentRuntime;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;

/**
 * 运行时会话工厂：统一创建/销毁 {@link RuntimeSession}。
 *
 * <p>存在的意义是消除<b>引导逻辑漂移</b>——此前 thin 主循环与 langgraph 主循环各自
 * new 一遍预算、RuntimeContext、MiddlewareContext 并各自记得调 onLoopStart/onLoopEnd，
 * 任何一处漏改都会让两个主循环的行为不一致（双主循环漂移的典型来源）。</p>
 */
public class RuntimeSessionFactory {

    private final CoreProperties properties;

    public RuntimeSessionFactory(CoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 开启一次编排会话：建立全局预算与上下文，并触发中间件链的循环开始钩子。
     *
     * <p>预算与回合上限统一经 {@link RunOverrides#from(ConversationCtx, CoreProperties)} 解析
     * （支持通道按次覆盖），并把解析结果同时绑到 {@link RuntimeSession#turnLimit()}，
     * 使主环的回合安全阀与预算作用域同源、不可能分叉。</p>
     */
    public RuntimeSession open(SessionRef ref, ConversationCtx ctx, AgentEventPublisher publisher,
                               AgentRuntime runtime) {
        RunOverrides overrides = RunOverrides.from(ctx, properties);
        BudgetScope global = new BudgetScope(BudgetLevel.GLOBAL,
                overrides.maxBudget(), 0L, overrides.turnLimit());
        RuntimeContext context = new RuntimeContext(ref, ctx, publisher, TraceContext.root(),
                new BudgetManager(global));
        MiddlewareContext loopContext = new MiddlewareContext(context);
        runtime.onLoopStart(loopContext);
        return new RuntimeSession(global, context, loopContext, overrides.turnLimit());
    }

    /** 结束一次编排会话：触发中间件链的循环结束钩子（保证日志/快照/指标收尾）。 */
    public void close(RuntimeSession session, AgentRuntime runtime) {
        if (session == null) {
            return;
        }
        runtime.onLoopEnd(session.loopContext());
    }
}
