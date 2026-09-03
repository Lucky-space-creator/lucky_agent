package com.lucky.agent.mcp.api.dto;

import java.util.Map;

/**
 * 从 MCP Server 远端拉取的 Tool 元数据（tools/list 结果项）。
 *
 * <p>映射为 agent-core 的 {@code Tool} 时，工具名为 {@code mcp.<serverId>.<toolName>}，
 * 描述与 inputSchema 透传给 LLM 理解与生成入参。</p>
 *
 * @param serverId    所属 MCP Server id
 * @param toolName    MCP 侧工具名
 * @param description 工具描述（远端未提供时为空串）
 * @param inputSchema 入参 JSON Schema（可为空）
 */
public record McpTool(
        String serverId,
        String toolName,
        String description,
        Map<String, Object> inputSchema) {
}
