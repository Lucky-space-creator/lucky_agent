package mcp.support.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import com.lucky.agent.mcp.support.transport.HttpTransport;
import com.lucky.agent.mcp.support.transport.JsonRpcResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 传输单元测试：用 JDK HttpServer 模拟 MCP 端点，验证
 * JSON 请求封装、会话头回传、SSE 响应解析。
 */
class HttpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> receivedSessionHeaders = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        JsonNode node = MAPPER.readTree(body);
        String method = node.path("method").asText();
        receivedSessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
            send(exchange, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":" + node.get("id")
                            + ",\"result\":{\"serverInfo\":{\"name\":\"demo-server\"}}}");
        } else if ("tools/list".equals(method)) {
            send(exchange, "text/event-stream",
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + node.get("id")
                            + ",\"result\":{\"tools\":[{\"name\":\"demo_tool\",\"description\":\"演示\","
                            + "\"inputSchema\":{\"type\":\"object\"}}]}}\n\n");
        } else {
            send(exchange, "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":" + node.get("id") + ",\"result\":{\"ok\":true}}");
        }
    }

    private void send(HttpExchange exchange, String contentType, String resp) throws IOException {
        byte[] out = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private McpServerDef def() {
        return new McpServerDef("demo", "Demo", "演示", McpServerDef.TYPE_HTTP,
                null, null, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Map.of(), true);
    }

    private HttpTransport newTransport() {
        return new HttpTransport(def(), new McpProperties(null, 0, 0, 0, 0), MAPPER);
    }

    @Test
    void testJsonRequestRoundTrip() {
        HttpTransport transport = newTransport();
        transport.start();

        JsonRpcResponse resp = transport.request("ping", Map.of("k", "v"), 5000);

        assertFalse(resp.isError());
        assertTrue(resp.resultMap().containsKey("ok"));
        transport.close();
    }

    @Test
    void testCapturesAndReusesSessionHeader() {
        HttpTransport transport = newTransport();
        transport.start();

        transport.request("initialize", Map.of(), 5000);
        transport.request("ping", Map.of(), 5000);

        // 第一次无会话头，第二次应回传 initialize 下发的 Mcp-Session-Id
        assertEquals(null, receivedSessionHeaders.get(0));
        assertEquals("sess-1", receivedSessionHeaders.get(1));
        transport.close();
    }

    @Test
    void testParsesSseToolsList() {
        HttpTransport transport = newTransport();
        transport.start();

        JsonRpcResponse resp = transport.request("tools/list", Map.of(), 5000);

        assertFalse(resp.isError());
        Object tools = resp.resultMap().get("tools");
        assertTrue(tools instanceof List<?>);
        Map<?, ?> firstTool = (Map<?, ?>) ((List<?>) tools).get(0);
        assertEquals("demo_tool", firstTool.get("name"));
        transport.close();
    }

    @Test
    void testServerErrorReturnsErrorResponse() {
        // 模拟服务端返回错误对象
        HttpServer errorServer = startErrorServer();
        McpServerDef errorDef = new McpServerDef("err", "Err", "错误服务", McpServerDef.TYPE_HTTP,
                null, null, "http://127.0.0.1:" + errorServer.getAddress().getPort() + "/mcp", Map.of(), true);
        HttpTransport transport = new HttpTransport(errorDef, new McpProperties(null, 0, 0, 0, 0), MAPPER);
        transport.start();

        JsonRpcResponse resp = transport.request("initialize", Map.of(), 5000);

        assertTrue(resp.isError());
        assertEquals("权限不足", resp.errorMessage());
        transport.close();
        errorServer.stop(0);
    }

    private HttpServer startErrorServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", exchange -> {
                String resp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,\"message\":\"权限不足\"}}";
                byte[] out = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
