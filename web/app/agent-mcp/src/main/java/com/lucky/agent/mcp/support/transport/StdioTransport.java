package com.lucky.agent.mcp.support.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输：拉起 MCP 子进程，经 stdin/stdout 传 JSON-RPC。
 *
 * <p>单请求-单响应对（基于 id）由 {@link #pending} 挂起表关联：读线程从 stdout 逐行
 * 解析，命中 pending 中的 id 即完成对应 future；写侧每条消息独占一行。进程退出/超时
 * 时将该时刻所有 pending 以错误响应落地，不向会话层抛异常。</p>
 *
 * <p><b>写侧必须有界且串行</b>（台账 #37）：子进程不读 stdin 时，管道缓冲区写满后
 * {@code write/flush} 会<b>无限期阻塞调用线程</b>——请求级超时只护住了「等响应」，
 * 护不住「发请求」。故写动作固定投递到单写线程执行并限时；单线程同时天然串行化
 * （JSON-RPC 按行分帧，并发写会把多条消息写进同一行，此前虽无人触发但属潜在损坏）。
 * 写超时的请求以 error 响应返回，管道恢复由 close() 收口。</p>
 */
@Slf4j
public class StdioTransport implements McpTransport {

    private static final int MAX_QUEUED_LINES = 512;

    /**
     * 关闭子进程的宽限期（毫秒）：先 destroy，超时强杀。
     *
     * <p>不能用请求级超时（{@code requestTimeoutMs}，默认 10s）：关闭是收尾动作，
     * 子进程不响应终止时等满请求超时会让「退出/重连」出现秒级抖动（实测 D10）。
     * 1.5s 足够正常进程优雅退出，超时一律强杀，保证收口有界。</p>
     */
    private static final long CLOSE_GRACE_MS = 1500L;

    /**
     * 单次 stdin 写入的超时（毫秒）。正常情况毫秒级完成；子进程不读管道时写会阻塞，
     * 到点放弃并以 error 响应返回，绝不把调用线程吊死。
     */
    static final long WRITE_TIMEOUT_MS = 5_000L;

    private final McpServerDef def;
    private final ObjectMapper objectMapper;
    private final AtomicLong idGen = new AtomicLong();

    private final Map<Long, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();

    /** 写侧串行锁：JSON-RPC 按行分帧，并发写会把多条消息写进同一行。 */
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile boolean closed;
    /** 写线程池（包可见，测试注入假执行器验证写超时；生产由 start() 创建单写线程）。 */
    volatile ExecutorService writeExecutor;
    private BufferedWriter writer;


    public StdioTransport(McpServerDef def, ObjectMapper objectMapper) {
        this.def = def;
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
            writeExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mcp-stdio-write-" + def.id());
                t.setDaemon(true);
                return t;
            });
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
        } catch (TimeoutException e) {
            log.warn("MCP 通知发送超时（子进程不消费 stdin）：{}#{}", def.id(), method);
        } catch (IOException e) {
            log.warn("MCP 通知发送失败：{}#{}", def.id(), method, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        failAllPending("MCP 传输已关闭：" + def.id());
        ExecutorService executor = writeExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
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
                if (!process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MCP stdio 已关闭：{}", def.id());
    }

    /** 子进程是否仍存活（台账 #38：此前会话层无从得知子进程已死，状态与实况长期错位）。 */
    @Override
    public boolean isAlive() {
        return !closed && process != null && process.isAlive();
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
                Object rawId = resp.id();
                // JSON-RPC 允许 id 为数字<b>或字符串</b>，对端把 id 字符串化是常见实现（台账 #30）：
                // 此前只认 Number，字符串 id 一律落进「通知」分支只打 debug，响应被丢弃 ——
                // 请求等满整个超时窗口才失败，日志里毫无线索。此处统一归一化为 long 再查挂起表。
                Long id = normalizeId(rawId);
                if (id == null) {
                    // 真正的通知/服务端请求（无 id）或结构异常的 id
                    log.debug("收到 MCP 服务端通知/异常响应：{}#{}", def.id(), line);
                    continue;
                }
                CompletableFuture<JsonRpcResponse> future = pending.remove(id);
                if (future != null) {
                    future.complete(resp);
                } else {
                    // 挂起表里没有：多半是已超时清理的迟到响应，或对端擅自改了 id。
                    // 提到 warn —— 否则「每次请求都等满超时就失败」在现场无任何可查痕迹。
                    log.warn("MCP 响应 id 未匹配到挂起请求（可能已超时或对端改写了 id）：{} id={} raw={}，响应行：{}",
                            def.id(), id, rawId, line);
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

    /**
     * 把响应里的 id 归一化为挂起表键。
     *
     * <p>兼容三种形态：JSON 数字、数字字符串（{@code "12"}）、以及带空白的数字字符串。
     * 无法解析为数字（如 UUID 字符串）时返回 null —— 调用方按「非本端请求」处理。</p>
     *
     * <p>包级可见以便单测直接覆盖 id 形态矩阵（读线程本身依赖真实子进程，不便做单测）。</p>
     */
    static Long normalizeId(Object rawId) {
        if (rawId instanceof Number number) {
            return number.longValue();
        }
        if (rawId instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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

    /** 有界写：投递到单写线程执行并限时（包可见，测试直接驱动超时路径）。 */
    void write(String json) throws IOException, TimeoutException {
        if (pending.size() > MAX_QUEUED_LINES) {
            throw new IOException("MCP 挂起请求过多：" + def.id());
        }
        ExecutorService executor = writeExecutor;
        if (executor == null || executor.isShutdown()) {
            throw new IOException("MCP 写通道已关闭：" + def.id());
        }
        try {
            CompletableFuture.runAsync(() -> doWrite(json), executor)
                    .get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("MCP stdin 写入失败：" + def.id(), cause);
        } catch (TimeoutException e) {
            // 子进程不读 stdin：写线程仍阻塞在管道上，由 close() 统一收口。
            // 这里必须立刻放弃，否则调用线程跟着一起死（台账 #37）。
            throw new TimeoutException("MCP 子进程长时间不消费 stdin：" + def.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP stdin 写入被中断：" + def.id(), e);
        }
    }

    /** 实际落盘写（固定跑在单写线程上；加锁仅为兜底，单线程本身已串行）。 */
    private void doWrite(String json) {
        synchronized (writeLock) {
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
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
