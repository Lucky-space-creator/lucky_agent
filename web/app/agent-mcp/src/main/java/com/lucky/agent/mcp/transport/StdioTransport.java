package com.lucky.agent.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输：拉起 MCP 子进程，经 stdin/stdout 传 JSON-RPC。
 *
 * <p>单请求-单响应对（基于 id）由 {@link #pending} 挂起表关联：读线程从 stdout 逐行
 * 解析，命中 pending 中的 id 即完成对应 future；写侧每条消息独占一行。进程退出/超时
 * 时将该时刻所有 pending 以错误响应落地，不向会话层抛异常。</p>
 */
@Slf4j
public class StdioTransport implements McpTransport {

    private static final int MAX_QUEUED_LINES = 512;

    private final McpServerDef def;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong idGen = new AtomicLong();

    private final Map<Long, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile boolean closed;
    private BufferedWriter writer;

    public StdioTransport(McpServerDef def, McpProperties properties, ObjectMapper objectMapper) {
        this.def = def;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        if (def.command() == null || def.command().isBlank()) {
            throw new IllegalStateException("stdio 模式缺少 command：" + def.id());
        }
        List<String> command = new ArrayList<>();
        command.add(def.command());
        if (def.args() != null) {
            command.addAll(def.args());
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (def.env() != null && !def.env().isEmpty()) {
                builder.environment().putAll(def.env());
            }
            builder.redirectErrorStream(false);
            process = builder.start();
            writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::readLoop, "mcp-stdio-read-" + def.id());
            reader.setDaemon(true);
            reader.start();
            Thread errDrain = new Thread(this::drainStderr, "mcp-stdio-err-" + def.id());
            errDrain.setDaemon(true);
            errDrain.start();
            log.info("MCP stdio 已启动：{} {}", def.id(), String.join(" ", command));
        } catch (IOException e) {
            closed = true;
            throw new IllegalStateException("启动 MCP 子进程失败：" + def.id(), e);
        }
    }

    @Override
    public JsonRpcResponse request(String method, Map<String, Object> params, long timeoutMs) {
        if (closed || process == null || !process.isAlive()) {
            return JsonRpcResponse.fail("MCP 子进程不可用：" + def.id());
        }
        long id = idGen.incrementAndGet();
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(requestJson(id, method, params));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            return JsonRpcResponse.fail("MCP 请求超时：" + def.id() + "#" + method);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            return JsonRpcResponse.fail("MCP 请求被中断：" + def.id() + "#" + method);
        } catch (Exception e) {
            pending.remove(id);
            return JsonRpcResponse.fail("MCP 请求发送失败：" + def.id() + "#" + method + " " + e.getMessage());
        }
    }

    @Override
    public void notify(String method, Map<String, Object> params) {
        if (closed || process == null || !process.isAlive()) {
            log.warn("MCP 子进程不可用，跳过通知：{}#{}", def.id(), method);
            return;
        }
        try {
            write(requestJson(null, method, params));
        } catch (IOException e) {
            log.warn("MCP 通知发送失败：{}#{}", def.id(), method, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        failAllPending("MCP 传输已关闭：" + def.id());
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("关闭 MCP stdin 失败（可忽略）：{}", def.id(), e);
            }
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(properties.requestTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MCP stdio 已关闭：{}", def.id());
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonRpcResponse resp = JsonRpcResponse.parse(objectMapper, line);
                Object id = resp.id();
                if (id instanceof Number number) {
                    CompletableFuture<JsonRpcResponse> future = pending.remove(number.longValue());
                    if (future != null) {
                        future.complete(resp);
                    }
                } else {
                    log.debug("收到 MCP 服务端通知/异常响应：{}#{}", def.id(), line);
                }
            }
        } catch (IOException e) {
            log.warn("MCP 读线程退出：{}", def.id(), e);
        } finally {
            if (!closed) {
                closed = true;
                failAllPending("MCP 子进程已退出：" + def.id());
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("MCP stderr[{}]: {}", def.id(), line);
            }
        } catch (IOException e) {
            log.debug("MCP stderr 读取结束：{}", def.id());
        }
    }

    private void failAllPending(String message) {
        pending.forEach((id, future) -> future.complete(JsonRpcResponse.fail(message)));
        pending.clear();
    }

    private void write(String json) throws IOException {
        if (pending.size() > MAX_QUEUED_LINES) {
            throw new IOException("MCP 挂起请求过多：" + def.id());
        }
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private String requestJson(Long id, String method, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"jsonrpc\":\"2.0\"");
        if (id != null) {
            sb.append(",\"id\":").append(id);
        }
        sb.append(",\"method\":\"").append(method).append("\"");
        sb.append(",\"params\":");
        try {
            sb.append(objectMapper.writeValueAsString(params == null ? Map.of() : params));
        } catch (Exception e) {
            sb.append("{}");
        }
        sb.append('}');
        return sb.toString();
    }
}
