package com.lucky.agent.mcp.support.connect;

import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.api.dto.McpTool;
import com.lucky.agent.mcp.config.McpProperties;
import com.lucky.agent.mcp.support.transport.JsonRpcResponse;
import com.lucky.agent.mcp.support.transport.McpTransport;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 会话：在单一传输之上编排 JSON-RPC 生命周期。
 *
 * <p>连接顺序：{@code initialize}（携带协议版本与客户端信息）→
 * {@code notifications/initialized} → {@code tools/list} 拉取工具列表缓存，
 * 之后 {@code tools/call} 调用、{@code ping} 心跳。任一步失败即抛出，
 * 由连接管理器标记 ERROR 并按探活重试。</p>
 */
@Slf4j
public class McpSession {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "lucky-agent";

    private final McpServerDef def;
    private final McpTransport transport;
    private final McpProperties properties;

    private volatile List<McpTool> tools = List.of();
    private volatile boolean connected;

    public McpSession(McpServerDef def, McpTransport transport, McpProperties properties) {
        this.def = def;
        this.transport = transport;
        this.properties = properties;
    }

    /** 建立连接并完成初始化握手与工具列表拉取；失败抛出由上层标记 ERROR。 */
    public void connect() {
        transport.start();
        Map<String, Object> initParams = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", CLIENT_NAME, "version", "1.0.0"));
        JsonRpcResponse initResp = transport.request("initialize", initParams, properties.requestTimeoutMs());
        if (initResp.isError()) {
            throw new IllegalStateException("initialize 失败[" + def.id() + "]：" + initResp.errorMessage());
        }
        transport.notify("notifications/initialized", Map.of());
        List<McpTool> fetched = fetchTools();
        if (fetched.isEmpty()) {
            throw new IllegalStateException("MCP 未暴露任何 Tool：" + def.id());
        }
        this.tools = fetched;
        this.connected = true;
        log.info("MCP 会话就绪：{}（{} 个 Tool）", def.id(), fetched.size());
    }

    /** 是否已连接。 */
    public boolean isConnected() {
        return connected;
    }

    /** 缓存的 Tool 列表。 */
    public List<McpTool> tools() {
        return tools;
    }

    /** 调用远端工具。 */
    public ToolResult call(String toolName, Map<String, Object> args) {
        Map<String, Object> params = new HashMap<>(Map.of("name", toolName));
        params.put("arguments", args == null ? Map.of() : args);
        JsonRpcResponse resp = transport.request("tools/call", params, properties.requestTimeoutMs());
        if (resp.isError()) {
            return ToolResult.error("MCP 调用失败[" + def.id() + "#" + toolName + "]：" + resp.errorMessage());
        }
        Map<String, Object> result = resp.resultMap();
        if (result == null) {
            return ToolResult.ok("ok");
        }
        String text = extractText(result);
        if (Boolean.TRUE.equals(result.get("isError"))) {
            return ToolResult.error(text);
        }
        return ToolResult.ok(text);
    }

    /** 心跳探测。 */
    public boolean ping() {
        long timeout = Math.max(properties.heartbeatSec() * 1000L, 3000L);
        JsonRpcResponse resp = transport.request("ping", Map.of(), timeout);
        return !resp.isError();
    }

    /** 关闭连接。 */
    public void close() {
        connected = false;
        tools = List.of();
        transport.close();
    }

    private List<McpTool> fetchTools() {
        JsonRpcResponse resp = transport.request("tools/list", Map.of(), properties.requestTimeoutMs());
        if (resp.isError()) {
            throw new IllegalStateException("tools/list 失败[" + def.id() + "]：" + resp.errorMessage());
        }
        Map<String, Object> result = resp.resultMap();
        if (result == null) {
            return List.of();
        }
        Object toolsNode = result.get("tools");
        if (!(toolsNode instanceof List<?> list)) {
            return List.of();
        }
        List<McpTool> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(new McpTool(def.id(),
                        str(m.get("name")),
                        str(m.get("description")),
                        castMap(m.get("inputSchema"))));
            }
        }
        return out;
    }

    /** 提取 tools/call 结果文本：优先 content[{type:text}]，其次 error 字段。 */
    private String extractText(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        Object contentNode = result.get("content");
        if (contentNode instanceof List<?> content) {
            for (Object item : content) {
                if (item instanceof Map<?, ?> part && "text".equals(part.get("type"))) {
                    Object text = part.get("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }
        }
        if (sb.length() == 0) {
            Object err = result.get("error");
            return err == null ? "ok" : String.valueOf(err);
        }
        return sb.toString();
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}
