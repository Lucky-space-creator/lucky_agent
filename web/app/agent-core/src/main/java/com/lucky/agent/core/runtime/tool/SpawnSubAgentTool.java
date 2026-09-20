package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.loop.SubAgentLauncher;

/**
 * 「启动子代理」工具：把原主循环里硬编码的「启动隔离子代理？」分支暴露为 LLM 可调用工具。
 *
 * <p>子代理在独立消息序列/预算/span 中执行，<b>只回摘要</b>；本工具把摘要作为观察回灌，
 * 因此主循环无需感知子代理的存在与调度细节。</p>
 *
 * <p>权限：子代理<b>不提升权限</b>，继承父上下文的工作区权限，故本工具只需只读级别。</p>
 */
public class SpawnSubAgentTool implements RuntimeTool {

    /** 当前嵌套深度（运行时属性键，根为 0）。 */
    public static final String ATTR_DEPTH = "subagentDepth";

    private final SubAgentLauncher launcher;

    public SpawnSubAgentTool(SubAgentLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public String name() {
        return "spawn_sub_agent";
    }

    @Override
    public String description() {
        return "启动一个隔离子代理执行独立子任务并返回摘要。适用于可并行、上下文无关、需要独立预算的子任务。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"task\":{\"type\":\"string\",\"description\":\"子任务描述，需自包含（子代理看不到父上下文）\"},"
                + "\"isolation\":{\"type\":\"string\",\"description\":\"隔离级别，默认 summary-only\"}"
                + "},\"required\":[\"task\"]}";
    }

    /**
     * 子代理较重且有副作用风险，失败不自动重试（真正需要重试时应由模型判断后重新派发）。
     */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
        String task = call.arg("task");
        if (task == null || task.isBlank()) {
            task = ctx.goal();
        }
        int depth = depth(ctx);
        ExecutionResult result = launcher.launch(task, ctx, depth);
        return result
                .withMeta("isolation", call.arg("isolation") == null ? "summary-only" : call.arg("isolation"))
                .withMeta("depth", depth);
    }

    private int depth(RuntimeContext ctx) {
        Object value = ctx.attribute(ATTR_DEPTH);
        return (value instanceof Number n) ? n.intValue() : 0;
    }
}
