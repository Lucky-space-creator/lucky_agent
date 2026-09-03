package com.lucky.agent.mcp.api;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.mcp.api.dto.McpTool;

import java.util.List;
import java.util.Map;

/**
 * MCP 连接管理契约。
 *
 * <p>对 {@code McpSession} 做懒连/重连代理：调用方对连接生命周期无感，
 * 未连接时获取工具/调用工具触发按需连接；连接失败返回降级结果而非抛异常。</p>
 */
public interface McpConnector {

    /** 未连接状态。 */
    String STATUS_STOPPED = "STOPPED";
    /** 已建立并初始化完成。 */
    String STATUS_RUNNING = "RUNNING";
    /** 连接失败/心跳超时。 */
    String STATUS_ERROR = "ERROR";

    /** 运行状态：{@link #STATUS_STOPPED} / {@link #STATUS_RUNNING} / {@link #STATUS_ERROR}。 */
    String status(String serverId);

    /** 是否已建立并初始化连接。 */
    boolean isConnected(String serverId);

    /** 建立（或复用）与某 MCP Server 的连接并完成初始化握手。 */
    void connect(String serverId);

    /** 关闭与某 MCP Server 的连接。 */
    void close(String serverId);

    /** 断开并重新连接某 MCP Server。 */
    void reconnect(String serverId);

    /** 心跳探测某 MCP Server 是否存活，失败返回 false。 */
    boolean ping(String serverId);

    /** 获取某 MCP Server 的 Tool 列表（未连接时按需连接，失败返回空列表）。 */
    List<McpTool> listTools(String serverId);

    /**
     * 调用某 MCP Server 上的工具。
     *
     * @param serverId MCP Server id
     * @param toolName 工具名
     * @param args     入参（透传远端）
     * @param ctx      会话执行上下文
     * @return 工具结果，未连接/调用失败以 ok=false 返回
     */
    ToolResult callTool(String serverId, String toolName, Map<String, Object> args, ConversationCtx ctx);
}
