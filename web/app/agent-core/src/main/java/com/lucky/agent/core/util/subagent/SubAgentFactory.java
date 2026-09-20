package com.lucky.agent.core.util.subagent;

import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 子代理工厂：基于 SubAgentSpec 契约创建子代理运行时。
 *
 * <p>子代理共享同一编排引擎、权限级别与执行臂硬边界；只能回摘要或结构化结果，
 * 不提升权限。{@code disallowedTools} 先减、{@code tools} 再限定在 spec 约束内。</p>
 */
public class SubAgentFactory {

    private final ModelRouter modelRouter;
    private final ToolGateway toolGateway;

    public SubAgentFactory(ModelRouter modelRouter, ToolGateway toolGateway) {
        this.modelRouter = modelRouter;
        this.toolGateway = toolGateway;
    }

    /**
     * 创建子代理运行时（模型 + 受限工具集）。
     *
     * @param spec        子代理定义
     * @param workspaceId 工作空间 ID
     * @return 子代理运行时
     */
    public SubAgentRuntime create(SubAgentSpec spec, String workspaceId, String sysPrompt) {
        ChatModel model = modelRouter.resolve();
        List<ToolSpecification> tools = applyToolIsolation(toolGateway.buildToolSpecifications(workspaceId), spec);
        return new SubAgentRuntime(model, tools, sysPrompt,
                spec.maxTurns() > 0 ? spec.maxTurns() : 20);
    }

    /**
     * 工具隔离：{@code disallowedTools} 先减，{@code tools} 再限定（契约 §4）。
     * 子代理不提升权限，能力面受 spec 约束。
     */
    private List<ToolSpecification> applyToolIsolation(List<ToolSpecification> all, SubAgentSpec spec) {
        Set<String> keep = new HashSet<>();
        for (ToolSpecification t : all) {
            keep.add(t.name());
        }
        if (spec.disallowedTools() != null && !spec.disallowedTools().isEmpty()) {
            keep.removeAll(spec.disallowedTools());
        }
        if (spec.tools() != null && !spec.tools().isEmpty()) {
            keep.retainAll(spec.tools());
        }
        final Set<String> finalKeep = keep;
        return all.stream().filter(t -> finalKeep.contains(t.name())).toList();
    }

    /** 子代理运行时（模型 + 受限工具 + 系统提示 + 步数上限）。 */
    public static class SubAgentRuntime {
        private final ChatModel model;
        private final List<ToolSpecification> tools;
        private final String sysPrompt;
        private final int maxTurns;

        public SubAgentRuntime(ChatModel model, List<ToolSpecification> tools,
                               String sysPrompt, int maxTurns) {
            this.model = model;
            this.tools = tools;
            this.sysPrompt = sysPrompt;
            this.maxTurns = maxTurns;
        }

        public ChatModel model() { return model; }
        public List<ToolSpecification> tools() { return tools; }
        public String sysPrompt() { return sysPrompt; }
        public int maxTurns() { return maxTurns; }
    }
}
