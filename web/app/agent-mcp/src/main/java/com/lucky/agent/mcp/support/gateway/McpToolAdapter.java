package com.lucky.agent.mcp.support.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolAnnotations;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.dto.McpTool;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MCP Tool → common {@link Tool} 适配器。
 *
 * <p>工具名为 {@code mcp.<serverId>.<toolName>}，便于 core 按前缀分发 MCP_INVOKE 事件；
 * 描述与入参 Schema 透传远端。执行时经 {@link McpConnector} 调远端，调用权限
 * 边界由「授权隔离（未授权不注入）+ 执行时会话权限」把关，危险操作不落地本机。</p>
 */
public class McpToolAdapter implements Tool {

    private final McpTool tool;
    private final McpConnector connector;

    public McpToolAdapter(McpTool tool, McpConnector connector) {
        this.tool = tool;
        this.connector = connector;
    }

    @Override
    public String name() {
        return "mcp." + tool.serverId() + "." + tool.toolName();
    }

    @Override
    public String description() {
        return tool.description() == null || tool.description().isBlank()
                ? "MCP 工具：" + tool.toolName()
                : tool.description();
    }

    @Override
    public ToolAnnotations annotations() {
        return ToolAnnotations.writableTool();
    }

    @Override
    public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
        return Mono.fromCallable(() ->
                connector.callTool(tool.serverId(), tool.toolName(), args, ctx));
    }
}
