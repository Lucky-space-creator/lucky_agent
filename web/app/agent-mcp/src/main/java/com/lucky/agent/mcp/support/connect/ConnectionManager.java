package com.lucky.agent.mcp.support.connect;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.api.dto.McpTool;
import com.lucky.agent.mcp.config.McpProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接管理器：对 {@link McpSession} 做懒连/重连代理，维护运行状态。
 *
 * <p>调用方（Tool 源 / 探活）对连接生命周期无感：未连接时取工具/调用工具按需连接，
 * 连接失败标记 ERROR 并记录日志，向下返回空列表/降级结果而非抛异常。</p>
 */
@Slf4j
public class ConnectionManager implements McpConnector {

    private final McpRegistry registry;
    private final McpTransportFactory transportFactory;
    private final McpProperties properties;

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> statuses = new ConcurrentHashMap<>();

    public ConnectionManager(McpRegistry registry, McpTransportFactory transportFactory,
                             McpProperties properties) {
        this.registry = registry;
        this.transportFactory = transportFactory;
        this.properties = properties;
    }

    @Override
    public String status(String serverId) {
        return statuses.getOrDefault(serverId, STATUS_STOPPED);
    }

    @Override
    public boolean isConnected(String serverId) {
        McpSession session = sessions.get(serverId);
        return session != null && session.isConnected();
    }

    @Override
    public synchronized void connect(String serverId) {
        if (isConnected(serverId)) {
            return;
        }
        McpServerDef def = registry.find(serverId).orElse(null);
        if (def == null || !def.enabled()) {
            statuses.put(serverId, STATUS_STOPPED);
            return;
        }
        McpSession session = new McpSession(def, transportFactory.create(def), properties);
        try {
            session.connect();
            sessions.put(serverId, session);
            statuses.put(serverId, STATUS_RUNNING);
            log.info("MCP 连接成功：{}", serverId);
        } catch (Exception e) {
            session.close();
            sessions.remove(serverId);
            statuses.put(serverId, STATUS_ERROR);
            log.warn("MCP 连接失败：{}", serverId, e);
        }
    }

    @Override
    public void close(String serverId) {
        McpSession session = sessions.remove(serverId);
        if (session != null) {
            session.close();
        }
        statuses.put(serverId, STATUS_STOPPED);
    }

    @Override
    public void reconnect(String serverId) {
        close(serverId);
        connect(serverId);
    }

    @Override
    public boolean ping(String serverId) {
        McpSession session = sessions.get(serverId);
        return session != null && session.isConnected() && session.ping();
    }

    @Override
    public List<McpTool> listTools(String serverId) {
        ensureConnected(serverId);
        McpSession session = sessions.get(serverId);
        if (session == null || !session.isConnected()) {
            return List.of();
        }
        return session.tools();
    }

    @Override
    public ToolResult callTool(String serverId, String toolName, Map<String, Object> args, ConversationCtx ctx) {
        ensureConnected(serverId);
        McpSession session = sessions.get(serverId);
        if (session == null || !session.isConnected()) {
            return ToolResult.error("MCP 服务未连接：" + serverId);
        }
        return session.call(toolName, args);
    }

    private void ensureConnected(String serverId) {
        if (!isConnected(serverId)) {
            connect(serverId);
        }
    }
}
