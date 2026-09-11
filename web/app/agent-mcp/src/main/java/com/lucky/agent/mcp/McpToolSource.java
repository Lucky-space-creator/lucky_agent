package com.lucky.agent.mcp;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.mcp.support.gateway.McpToolAdapter;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.api.dto.McpTool;
import com.lucky.agent.mcp.support.auth.UserAuthIsolator;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具来源：仅将「启用且已授权」的 MCP Server 的 Tool 注入编排引擎。
 *
 * <p>授权隔离作为注册边界主闸门：未授权 Server 的 Tool 不出现，LLM 不可见；
 * 取消授权后下一轮工具构建即从网关消失。MCP 工具数量有限，不做目标语义召回
 * 的全量注入（与 Skill 的 Top-K 区分）。</p>
 */
public class McpToolSource implements ToolSource {

    private final McpRegistry registry;
    private final McpConnector connector;
    private final UserAuthIsolator auth;

    public McpToolSource(McpRegistry registry, McpConnector connector, UserAuthIsolator auth) {
        this.registry = registry;
        this.connector = connector;
        this.auth = auth;
    }

    @Override
    public String namespace() {
        return "mcp";
    }

    @Override
    public List<Tool> tools(String workspaceId, String goal) {
        List<Tool> tools = new ArrayList<>();
        for (McpServerDef def : registry.listEnabled()) {
            if (!auth.isAuthorized(def.id())) {
                continue;
            }
            for (McpTool tool : connector.listTools(def.id())) {
                tools.add(new McpToolAdapter(tool, connector));
            }
        }
        return tools;
    }
}
