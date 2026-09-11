package mcp.support.connect;

import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import com.lucky.agent.mcp.support.connect.McpSession;
import com.lucky.agent.mcp.support.transport.JsonRpcResponse;
import com.lucky.agent.mcp.support.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 会话单元测试：以假传输驱动握手序列、工具列表拉取、调用映射与心跳。
 */
class McpSessionTest {

    private final McpProperties props = new McpProperties(null, 0, 0, 0, 0);

    private McpSession newSession(FakeTransport transport) {
        return new McpSession(def("demo"), transport, props);
    }

    @Test
    void testConnectRunsHandshakeAndFetchesTools() {
        FakeTransport transport = new FakeTransport();
        McpSession session = newSession(transport);

        session.connect();

        assertTrue(session.isConnected());
        assertEquals(1, session.tools().size());
        assertEquals("toolA", session.tools().get(0).toolName());
        assertEquals(List.of("start", "initialize", "notify:notifications/initialized", "tools/list"), transport.calls);
    }

    @Test
    void testCallToolMapsTextContent() {
        FakeTransport transport = new FakeTransport();
        McpSession session = newSession(transport);
        session.connect();

        ToolResult result = session.call("toolA", Map.of("k", "v"));

        assertTrue(result.ok());
        assertEquals("你好，世界", result.data());
    }

    @Test
    void testCallToolSurfacesRemoteError() {
        FakeTransport transport = new FakeTransport();
        transport.overrides.put("tools/call", JsonRpcResponse.fail("远端拒绝"));
        McpSession session = newSession(transport);
        session.connect();

        ToolResult result = session.call("toolA", Map.of());

        assertTrue(result.isError());
        assertTrue(result.error().contains("远端拒绝"));
    }

    @Test
    void testCallToolIsErrorFlag() {
        FakeTransport transport = new FakeTransport();
        Map<String, Object> isErrorResult = new HashMap<>();
        isErrorResult.put("isError", true);
        isErrorResult.put("content", List.of(textPart("处理失败")));
        transport.overrides.put("tools/call", ok(3L, isErrorResult));
        McpSession session = newSession(transport);
        session.connect();

        ToolResult result = session.call("toolA", Map.of());

        assertTrue(result.isError());
        assertEquals("处理失败", result.error());
    }

    @Test
    void testPingReturnsAlive() {
        FakeTransport transport = new FakeTransport();
        McpSession session = newSession(transport);
        session.connect();

        assertTrue(session.ping());
    }

    @Test
    void testConnectFailsOnInitializeError() {
        FakeTransport transport = new FakeTransport();
        transport.failInitialize = true;
        McpSession session = newSession(transport);

        assertThrows(IllegalStateException.class, session::connect);
        assertFalse(session.isConnected());
    }

    private McpServerDef def(String id) {
        return new McpServerDef(id, id, "演示", McpServerDef.TYPE_STDIO,
                "echo", List.of(), null, Map.of(), true);
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> part = new HashMap<>();
        part.put("type", "text");
        part.put("text", text);
        return part;
    }

    private static JsonRpcResponse ok(Object id, Map<String, Object> result) {
        return new JsonRpcResponse(id, result, null);
    }

    /** 假传输：记录调用序列，按方法返回预设响应。 */
    private static class FakeTransport implements McpTransport {

        final List<String> calls = new ArrayList<>();
        final Map<String, JsonRpcResponse> overrides = new HashMap<>();
        boolean failInitialize;

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public JsonRpcResponse request(String method, Map<String, Object> params, long timeoutMs) {
            calls.add(method);
            JsonRpcResponse override = overrides.get(method);
            if (override != null) {
                return override;
            }
            return switch (method) {
                case "initialize" -> failInitialize
                        ? JsonRpcResponse.fail("拒绝初始化")
                        : ok(1L, new HashMap<>(Map.of("serverInfo", "fake")));
                case "tools/list" -> {
                    Map<String, Object> tool = new HashMap<>();
                    tool.put("name", "toolA");
                    tool.put("description", "工具 A");
                    tool.put("inputSchema", Map.of("type", "object"));
                    Map<String, Object> result = new HashMap<>();
                    result.put("tools", List.of(tool));
                    yield ok(2L, result);
                }
                case "tools/call" -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("content", List.of(textPart("你好，世界")));
                    yield ok(3L, result);
                }
                case "ping" -> ok(4L, new HashMap<>());
                default -> JsonRpcResponse.fail("未知方法 " + method);
            };
        }

        @Override
        public void notify(String method, Map<String, Object> params) {
            calls.add("notify:" + method);
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }
}
