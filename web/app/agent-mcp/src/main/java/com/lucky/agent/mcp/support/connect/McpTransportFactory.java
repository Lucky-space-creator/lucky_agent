package com.lucky.agent.mcp.support.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import com.lucky.agent.mcp.support.transport.HttpTransport;
import com.lucky.agent.mcp.support.transport.McpTransport;
import com.lucky.agent.mcp.support.transport.StdioTransport;

/**
 * 传输工厂：按 MCP Server 类型（stdio/HTTP）构造对应传输实现。
 */
public class McpTransportFactory {

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpTransportFactory(McpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造传输实例。
     *
     * @param def MCP Server 定义（type 决定实现）
     * @return 对应传输
     */
    public McpTransport create(McpServerDef def) {
        if (McpServerDef.TYPE_HTTP.equals(def.type())) {
            return new HttpTransport(def, properties, objectMapper);
        }
        return new StdioTransport(def, properties, objectMapper);
    }
}
