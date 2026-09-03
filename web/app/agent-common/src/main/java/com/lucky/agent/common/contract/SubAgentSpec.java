package com.lucky.agent.common.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 子代理定义（契约 §4），Phase 1 定死。
 *
 * <p>子代理只回摘要或结构化结果，不直接回灌完整上下文；{@code disallowedTools} 先减，
 * {@code tools} 再限定；子代理不提升权限，不绕过 ASK 与执行臂硬边界。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentSpec(
        String id,
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        int maxTurns,
        long maxTokens,
        String permissionMode,
        boolean summaryOnly,
        String isolation,
        String persona,
        List<String> skills) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String name;
        private String description;
        private List<String> tools;
        private List<String> disallowedTools;
        private int maxTurns = 20;
        private long maxTokens = 50000;
        private String permissionMode = "default";
        private boolean summaryOnly = true;
        private String isolation = "none";
        private String persona;
        private List<String> skills = List.of();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxTokens(long maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder permissionMode(String permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public Builder summaryOnly(boolean summaryOnly) {
            this.summaryOnly = summaryOnly;
            return this;
        }

        public Builder isolation(String isolation) {
            this.isolation = isolation;
            return this;
        }

        public Builder persona(String persona) {
            this.persona = persona;
            return this;
        }

        public Builder skills(List<String> skills) {
            this.skills = skills;
            return this;
        }

        public SubAgentSpec build() {
            return new SubAgentSpec(id, name, description, tools, disallowedTools, maxTurns, maxTokens,
                    permissionMode, summaryOnly, isolation, persona, skills);
        }
    }
}
