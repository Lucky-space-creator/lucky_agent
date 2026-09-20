package com.lucky.agent.core.repository;

import java.util.List;

/**
 * PLAN 阶段模型产出的「子代理任务」意图（LLM 决策层 → SubAgentSpec 的中间态）。
 *
 * <p>仅当 LLM 判定某子问题需要隔离执行（独立上下文/受限工具/并行高复杂分支）时才产出；
 * 普通拆分用 {@code steps}，无需子代理。</p>
 *
 * @param id            子代理标识（本计划内唯一，如 sa-1）
 * @param name          子代理名
 * @param task          要交给子代理的独立子任务描述
 * @param tools         允许使用的工具名子集（null/空=不限）；来自白名单限制
 * @param disallowedTools 禁止使用的工具
 * @param permissionMode 权限模式（default / acceptEdits / bypassPermissions）
 * @param summaryOnly   是否只回摘要（默认 true）
 */
public record SubAgentIntent(
        String id,
        String name,
        String task,
        List<String> tools,
        List<String> disallowedTools,
        String permissionMode,
        boolean summaryOnly) {

    public static SubAgentIntent of(String id, String name, String task) {
        return new SubAgentIntent(id, name, task, null, null, "default", true);
    }
}
