package com.lucky.agent.mcp.support.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP 传输：对 MCP HTTP/SSE 端点逐请求 POST JSON-RPC。
 *
 * <p>请求体按 JSON-RPC 2.0 封装；初始化握手后捕获服务端下发的
 * {@code Mcp-Session-Id} 头并回传后续请求。响应可为纯 JSON 或 SSE
 * 流（逐 {@code data:} 行提取 payload），统一解析为 {@link JsonRpcResponse}。</p>
 */
@Slf4j
public class HttpTransport implements McpTransport {

    private static final Duration HTTP_CONNECT = Duration.ofSeconds(5);

    private final McpServerDef def;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final AtomicLong idGen = new AtomicLong();

    private volatile String sessionId;

    public HttpTransport(McpServerDef def, McpProperties properties, ObjectMapper objectMapper) {
        this.def = def;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT).build();
    }

    @Override
    public void start() {
        if (def.endpointUrl() == null || def.endpointUrl().isBlank()) {
            throw new IllegalStateException("HTTP 模式缺少 endpointUrl：" + def.id());
        }
        log.info("MCP HTTP 就绪：{} -> {}", def.id(), def.endpointUrl());
    }

    @Override
    public JsonRpcResponse request(String method, Map<String, Object> params, long timeoutMs) {
        long id = idGen.incrementAndGet();
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "method", method,
                    "params", params == null ? Map.of() : params));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(def.endpointUrl()))
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 120_000)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
            return parseBody(resp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonRpcResponse.fail("MCP HTTP 请求被中断：" + def.id() + "#" + method);
        } catch (Exception e) {
            log.warn("MCP HTTP 请求失败：{}#{}", def.id(), method, e);
            return JsonRpcResponse.fail("MCP HTTP 请求失败：" + def.id() + "#" + method + " " + e.getMessage());
        }
    }

    @Override
    public void notify(String method, Map<String, Object> params) {
        // MCP HTTP 协议允许以 notification 形式发送；此处按请求发送（无 id 无响应），
        // 远端通常忽略非订阅类通知，失败仅记日志不影响调用链。
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params == null ? Map.of() : params));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(def.endpointUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("MCP HTTP 通知失败（可忽略）：{}#{}", def.id(), method, e);
        }
    }

    @Override
    public void close() {
        log.info("MCP HTTP 已关闭：{}", def.id());
    }

    private JsonRpcResponse parseBody(HttpResponse<String> resp) {
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return JsonRpcResponse.fail("MCP HTTP 空响应：" + def.id());
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream") || !body.trim().startsWith("{")) {
            return parseSse(body);
        }
        return JsonRpcResponse.parse(objectMapper, body);
    }

    private JsonRpcResponse parseSse(String body) {
        StringBuilder json = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    continue;
                }
                json.append(payload);
            }
        }
        if (json.length() == 0) {
            return JsonRpcResponse.fail("MCP SSE 空响应：" + def.id());
        }
        return JsonRpcResponse.parse(objectMapper, json.toString());
    }
}
