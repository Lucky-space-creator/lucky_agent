package com.lucky.agent.core.runtime.loop;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;

/**
 * 子代理启动器：把「怎么起一个隔离子代理」抽象出来，使 {@code spawn_sub_agent} 工具
 * 不直接依赖具体执行器实现（可切换真实执行器 / 测试替身 / 远程执行器）。
 *
 * <p>隔离约定：子代理拥有独立消息序列、独立预算与独立 span，只回摘要；
 * 不提升权限、不绕过 ASK 与执行臂硬边界。</p>
 */
public interface SubAgentLauncher {

    /**
     * 启动一个子代理并等待其摘要返回。
     *
     * @param task  子任务描述
     * @param ctx   父运行时上下文（提供 workspaceId/sessionId/预算）
     * @param depth 嵌套深度（根为 0）
     * @return 统一执行结果（output = 摘要）
     */
    ExecutionResult launch(String task, RuntimeContext ctx, int depth);
}
