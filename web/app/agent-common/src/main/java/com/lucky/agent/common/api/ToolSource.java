package com.lucky.agent.common.api;

import java.util.List;

/**
 * 工具来源注册契约（开闭原则：新增工具对 core 无侵入）。
 *
 * <p>能力模块（executor/skill/mcp）实现本接口并作为 Spring Bean 注册，
 * core 的 {@code ToolGateway} 在构建工具集时自动聚合全部来源的工具。
 * 模块只依赖本接口与 common 的 {@link Tool}，core 不反向依赖任何业务模块。</p>
 */
public interface ToolSource {

    /**
     * 来源命名空间（如 {@code skill} / {@code mcp}），用于日志与事件归因，
     * 与工具名前缀保持一致（如 {@code skill.xxx} / {@code mcp.yyy.zzz}）。
     *
     * @return 命名空间标识
     */
    String namespace();

    /**
     * 为工作空间构建本来源的工具列表。
     *
     * <p>每次构建实时生成（而非缓存），以便热插拔（Skill/MCP 增删启停）在下一轮
     * 推理即生效。{@code goal} 为当前任务目标/意图，用于 Skill 语义 Top-K 按需注入
     * （未命中不注入，省 token）；不关心目标的来源（如 MCP）可忽略该参数。</p>
     *
     * @param workspaceId 工作空间 ID
     * @param goal        当前任务目标（可能为 null，表示无明确意图）
     * @return 本来源提供的工具实例列表
     */
    List<Tool> tools(String workspaceId, String goal);
}
