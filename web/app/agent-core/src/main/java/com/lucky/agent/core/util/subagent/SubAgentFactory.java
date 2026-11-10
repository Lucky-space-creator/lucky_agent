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
 *
 * <p><b>台账 #15</b>：隔离此前只作用于「给模型看的 {@code toolSpecifications}」，
 * 而 {@code SubAgentExecutor} 分发工具时走的是 {@code ToolGateway.dispatch}——
 * 它在<b>全量</b>工具表里按名字查找。于是模型幻觉或被注入一个未声明工具（如
 * {@code shell.exec}）仍会真的执行。现在把允许集合随运行时一起下发，由执行器在
 * 分发前硬校验，{@code disallowedTools} 才真正生效。</p>
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
        List<ToolSpecification> all = toolGateway.buildToolSpecifications(workspaceId);
        Set<String> allowed = allowedToolNames(all, spec);
        List<ToolSpecification> tools = all.stream().filter(t -> allowed.contains(t.name())).toList();
        return new SubAgentRuntime(model, tools, allowed, sysPrompt,
                spec.maxTurns() > 0 ? spec.maxTurns() : 20);
    }

    /**
     * 工具隔离：{@code disallowedTools} 先减，{@code tools} 再限定（契约 §4）。
     * 子代理不提升权限，能力面受 spec 约束。
     *
     * @return 允许的工具名集合（既用于过滤 schema 清单，也用于执行前硬校验）
     */
    public Set<String> allowedToolNames(List<ToolSpecification> all, SubAgentSpec spec) {
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
        return Set.copyOf(keep);
    }

    /** 子代理运行时（模型 + 受限工具 + 允许集合 + 系统提示 + 步数上限）。 */
    public static class SubAgentRuntime {
        private final ChatModel model;
        private final List<ToolSpecification> tools;
        private final Set<String> allowedToolNames;
        private final String sysPrompt;
        private final int maxTurns;

        public SubAgentRuntime(ChatModel model, List<ToolSpecification> tools,
                               String sysPrompt, int maxTurns) {
            this(model, tools, tools.stream().map(ToolSpecification::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()), sysPrompt, maxTurns);
        }

        public SubAgentRuntime(ChatModel model, List<ToolSpecification> tools,
                               Set<String> allowedToolNames, String sysPrompt, int maxTurns) {
            this.model = model;
            this.tools = tools;
            this.allowedToolNames = allowedToolNames == null ? Set.of() : Set.copyOf(allowedToolNames);
            this.sysPrompt = sysPrompt;
            this.maxTurns = maxTurns;
        }

        public ChatModel model() { return model; }
        public List<ToolSpecification> tools() { return tools; }
        public String sysPrompt() { return sysPrompt; }
        public int maxTurns() { return maxTurns; }

        /** 该工具是否在子代理允许范围内（执行前硬校验，台账 #15）。 */
        public boolean allows(String toolName) {
            return toolName != null && allowedToolNames.contains(toolName);
        }

        /** 允许集合（只读）。 */
        public Set<String> allowedToolNames() { return allowedToolNames; }
    }
}
