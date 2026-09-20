package com.lucky.agent.core.runtime.loop;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import com.lucky.agent.core.runtime.middleware.Middleware;
import com.lucky.agent.core.runtime.middleware.MiddlewareChain;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.runtime.strategy.StrategyPlugin;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import com.lucky.agent.core.runtime.verify.Verifier;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行时（厚运行时）：承载被下沉的全部复杂度——中间件链、工具注册、策略插件、验证器、记忆端口、子代理池。
 * <p>薄主循环只调用本运行时的少量方法；新增关注点通过「加一个中间件/工具/策略」完成，无需改动主循环。</p>
 *
 * <p>工具分发链：{@code beforeTool → aroundTool(权限/预算/重试/日志 环绕) → afterTool}，
 * 因此权限裁决、重试退避等横切能力对工具实现透明。</p>
 */
public class AgentRuntime {

    private final MiddlewareChain middleware;
    private final ToolRegistry tools;
    private final List<StrategyPlugin> strategies;
    private final Verifier verifier;
    private final MemoryPort memory;
    private final SubAgentPool subAgentPool;

    public AgentRuntime(List<Middleware> middleware,
                        ToolRegistry tools,
                        List<StrategyPlugin> strategies,
                        Verifier verifier,
                        MemoryPort memory,
                        SubAgentPool subAgentPool) {
        this.middleware = new MiddlewareChain(middleware);
        this.tools = (tools == null) ? new ToolRegistry(List.of()) : tools;
        this.strategies = (strategies == null ? List.<StrategyPlugin>of() : strategies).stream()
                .sorted(Comparator.comparingInt(StrategyPlugin::order))
                .toList();
        this.verifier = verifier;
        this.memory = memory;
        this.subAgentPool = subAgentPool;
    }

    /** 便捷构造：内部自建工具注册表（测试/嵌入式使用）。 */
    public AgentRuntime(List<Middleware> middleware,
                        List<RuntimeTool> tools,
                        List<StrategyPlugin> strategies,
                        Verifier verifier,
                        MemoryPort memory,
                        SubAgentPool subAgentPool) {
        this(middleware, new ToolRegistry(tools), strategies, verifier, memory, subAgentPool);
    }

    // ---------------- 中间件钩子（薄循环调用点） ----------------

    public MiddlewareChain middleware() {
        return middleware;
    }

    public void onLoopStart(MiddlewareContext ctx) {
        middleware.onLoopStart(ctx);
    }

    /** LLM 调用前：返回 false 表示被中间件阻断（如预算耗尽）。 */
    public boolean beforeLlm(MiddlewareContext ctx) {
        middleware.beforeLlm(ctx);
        return !ctx.blocked();
    }

    public void afterLlm(MiddlewareContext ctx) {
        middleware.afterLlm(ctx);
    }

    public void onLoopEnd(MiddlewareContext ctx) {
        middleware.onLoopEnd(ctx);
    }

    // ---------------- 工具（LLM 可调用） ----------------

    /**
     * 分发一次工具调用：通知型钩子做预检，环绕型钩子包裹真实执行（重试/降级/计时）。
     * <p>被阻断时优先返回中间件写入的语义化结果（如 ASK），否则返回失败。</p>
     */
    public ExecutionResult invokeTool(ToolCall call, RuntimeContext rc) {
        MiddlewareContext ctx = new MiddlewareContext(rc);
        ctx.toolCall(call);
        middleware.beforeTool(ctx);
        if (ctx.blocked()) {
            ExecutionResult pending = ctx.result();
            return pending != null ? pending
                    : ExecutionResult.failure("工具被中间件阻断: " + ctx.blockReason());
        }

        ExecutionResult result = middleware.aroundTool(ctx, () -> tools.invoke(call, rc));
        ctx.result(result);
        middleware.afterTool(ctx);
        return result;
    }

    public ExecutionResult invokeTool(String name, Map<String, Object> args, RuntimeContext rc) {
        return invokeTool(ToolCall.of(name, args), rc);
    }

    public List<Map<String, Object>> toolSpecs() {
        return tools.specs();
    }

    public ToolRegistry tools() {
        return tools;
    }

    // ---------------- 策略 / 验证 / 记忆 / 子代理 ----------------

    public StrategyPlugin selectStrategy(RuntimeContext rc) {
        return strategies.stream().filter(s -> s.applies(rc)).findFirst().orElse(null);
    }

    /** 按策略选择本次使用的验证器（策略可覆盖默认验证器）。 */
    public Verifier verifierFor(RuntimeContext rc) {
        StrategyPlugin strategy = selectStrategy(rc);
        return (strategy == null) ? verifier : strategy.verifier(rc, verifier);
    }

    public List<StrategyPlugin> strategies() {
        return strategies;
    }

    public Verifier verifier() {
        return verifier;
    }

    public MemoryPort memory() {
        return memory;
    }

    public SubAgentPool subAgents() {
        return subAgentPool;
    }
}
