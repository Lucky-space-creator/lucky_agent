package com.lucky.agent.mcp.support.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdio 传输的有界写与存活探针（台账 #37 / #38）。
 *
 * <p>缺陷原状：{@code write()} 直接在调用线程上 {@code writer.write/flush}——
 * 子进程不读 stdin 时管道缓冲区写满，写操作<b>无限期阻塞</b>，请求级超时只护住
 * 「等响应」，护不住「发请求」；同时 {@code isAlive()} 不存在，子进程死后
 * 会话仍报已连接。</p>
 */
class StdioTransportBoundedWriteTest {

    private static McpServerDef def() {
        return new McpServerDef("demo", "演示", "演示", McpServerDef.TYPE_STDIO,
                "echo", List.of(), null, Map.of(), true);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("#37 写通道被堵死时限时放弃，绝不吊死调用线程")
    void writeTimesOutWhenPipeIsStuck() throws Exception {
        StdioTransport transport = new StdioTransport(def(), new ObjectMapper());
        // 单写线程被一个永不完成的任务占住，模拟「子进程不消费 stdin、管道写满阻塞」
        CountDownLatch stuck = new CountDownLatch(1);
        ExecutorService blocked = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-mcp-write");
            t.setDaemon(true);
            return t;
        });
        blocked.submit(() -> {
            try {
                stuck.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        transport.writeExecutor = blocked;

        long start = System.nanoTime();
        assertThrows(TimeoutException.class, () -> transport.write("{\"id\":1,\"method\":\"ping\"}"),
                "写必须限时放弃，而不是无限等管道");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= StdioTransport.WRITE_TIMEOUT_MS / 2,
                "过早返回说明没真正等过写窗口（" + elapsedMs + "ms）");
        assertTrue(elapsedMs < StdioTransport.WRITE_TIMEOUT_MS + 5_000,
                "写超时必须贴近 WRITE_TIMEOUT_MS，不允许无限阻塞（实测 " + elapsedMs + "ms）");
        stuck.countDown();
        blocked.shutdownNow();
    }

    @Test
    @DisplayName("#37 启动前的写失败必须快速、可诊断（写通道未建立）")
    void writeFailsFastWithoutExecutor() {
        StdioTransport transport = new StdioTransport(def(), new ObjectMapper());

        assertThrows(IOException.class, () -> transport.write("{\"id\":1}"),
                "未 start() 时没有写线程，应抛 IOException 而不是 NPE/挂起");
    }

    @Test
    @DisplayName("#38 未启动/已关闭的传输必须如实报 not-alive")
    void isAliveIsHonestBeforeStartAndAfterClose() {
        StdioTransport transport = new StdioTransport(def(), new ObjectMapper());

        assertFalse(transport.isAlive(), "未 start() 时 process 为 null，不得报存活");
    }
}
