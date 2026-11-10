package core.support.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolAnnotations;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.executor.api.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolGateway 批量分发行为验收：只读工具并行、写工具串行、结果保序、未知工具报错。
 */
class ToolGatewayTest {

    private FileService fileService;
    private ToolGateway gateway;
    private ConversationCtx ctx;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        gateway = new ToolGateway(fileService, List.of());
        ctx = ConversationCtx.builder()
                .sessionRef(SessionRef.of("s1", "u1", "ws1"))
                .build();
    }

    @Test
    void readOnlyToolsRunInParallel() throws InterruptedException {
        int n = 3;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch allActive = new CountDownLatch(n);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(fileService.read(anyString(), anyString())).thenAnswer(inv -> {
            startGate.await(2, TimeUnit.SECONDS);
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            allActive.countDown();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return Mono.just(ExecResult.success(null, "r").content("data"));
        });

        long start = System.currentTimeMillis();
        Thread trigger = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            startGate.countDown();
        });
        trigger.start();
        List<ToolResult> results = gateway.dispatchAll(List.of(
                new ToolGateway.ToolCall("file.read", Map.of("path", "a.txt")),
                new ToolGateway.ToolCall("file.read", Map.of("path", "b.txt")),
                new ToolGateway.ToolCall("file.read", Map.of("path", "c.txt"))
        ), ctx);
        long cost = System.currentTimeMillis() - start;

        allActive.await(2, TimeUnit.SECONDS);
        assertTrue(peak.get() == n, "只读工具应并行执行，峰值并发应等于 " + n + "，实际 " + peak.get());
        // 并行性由峰值并发断言保证；耗时仅作「未异常挂起」的冒烟阈值，放宽以容忍机器负载
        assertTrue(cost < 800, "并行总耗时应远小于串行累加（3×120ms=360ms），实际 " + cost + "ms");
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> !r.isError()), "只读工具应全部成功");
    }

    @Test
    void writeToolsRunSerially() {
        List<Long> startTimes = new ArrayList<>();
        when(fileService.write(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            startTimes.add(System.nanoTime());
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Mono.just(ExecResult.success(null, "w").summary("ok"));
        });

        gateway.dispatchAll(List.of(
                new ToolGateway.ToolCall("file.write", Map.of("path", "a.txt", "content", "x")),
                new ToolGateway.ToolCall("file.write", Map.of("path", "b.txt", "content", "y"))
        ), ctx);

        assertEquals(2, startTimes.size(), "写工具应逐个执行");
        long gapNs = startTimes.get(1) - startTimes.get(0);
        assertTrue(gapNs >= 100_000_000L,
                "写工具应串行：两次开始间隔应 ≥ 100ms，实际 " + gapNs / 1_000_000 + "ms");
    }

    @Test
    void resultsKeepInputOrder() {
        when(fileService.read(anyString(), anyString())).thenAnswer(inv ->
                Mono.just(ExecResult.success(null, "r").content("read-result")));
        when(fileService.write(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Mono.just(ExecResult.success(null, "w").summary("write-result")));
        when(fileService.stat(anyString(), anyString())).thenAnswer(inv ->
                Mono.just(ExecResult.success(null, "s").summary("stat-result")));

        List<ToolResult> results = gateway.dispatchAll(List.of(
                new ToolGateway.ToolCall("file.read", Map.of("path", "a.txt")),
                new ToolGateway.ToolCall("file.write", Map.of("path", "b.txt", "content", "x")),
                new ToolGateway.ToolCall("file.stat", Map.of("path", "c.txt")),
                new ToolGateway.ToolCall("no.such.tool", Map.of())
        ), ctx);

        assertEquals(4, results.size(), "结果应与输入一一对应");
        assertTrue(String.valueOf(results.get(0).data()).contains("read-result"));
        assertTrue(String.valueOf(results.get(1).data()).contains("write-result"));
        assertTrue(String.valueOf(results.get(2).data()).contains("stat-result"));
        assertTrue(results.get(3).isError(), "未知工具应返回错误");
        assertNull(results.get(3).data());
    }

    @Test
    void readToolExceptionIsIsolatedToItsOwnResult() {
        // 对应缺陷：单个只读工具抛异常会让 Flux.merge(...).block() 冒到 ReactEngine 外层 catch，
        // 整轮返回 EngineRunResult.error。修复后应降级为「该调用自己的 ToolResult.error」。
        ToolGateway gw = gatewayWith(fileService,
                stubTool("boom.read", true, Mono.error(new IllegalStateException("Timeout on blocking read"))));
        when(fileService.read(anyString(), anyString()))
                .thenReturn(Mono.just(ExecResult.success(null, "r").content("read-result")));
        when(fileService.write(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(ExecResult.success(null, "w").summary("write-result")));

        List<ToolResult> results = gw.dispatchAll(List.of(
                new ToolGateway.ToolCall("file.read", Map.of("path", "a.txt")),
                new ToolGateway.ToolCall("boom.read", Map.of()),
                new ToolGateway.ToolCall("file.write", Map.of("path", "b.txt", "content", "x")),
                new ToolGateway.ToolCall("no.such.tool", Map.of())
        ), ctx);

        assertEquals(4, results.size(), "失败不能让结果列表缺项");
        assertTrue(results.get(0).ok(), "同批只读工具不受影响");
        assertTrue(results.get(1).isError(), "异常只读工具应降级为 error 结果");
        assertTrue(String.valueOf(results.get(1).error()).contains("boom.read"));
        assertTrue(results.get(2).ok(), "后续写工具仍应执行");
        assertTrue(results.get(3).isError(), "未知工具仍返回 error");
    }

    @Test
    void writeToolExceptionDoesNotAbortFollowingSerialCalls() {
        when(fileService.write(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("disk full"))
                .thenReturn(Mono.just(ExecResult.success(null, "w").summary("write-result")));

        List<ToolResult> results = gateway.dispatchAll(List.of(
                new ToolGateway.ToolCall("file.write", Map.of("path", "a.txt", "content", "x")),
                new ToolGateway.ToolCall("file.write", Map.of("path", "b.txt", "content", "y"))
        ), ctx);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isError(), "同步抛异常的写工具应转成 error 结果");
        assertTrue(String.valueOf(results.get(0).error()).contains("file.write"));
        assertTrue(results.get(1).ok(), "前一个写工具异常不应中断后续写工具");
    }

    @Test
    void emptyToolMonoBecomesErrorResult() {
        ToolGateway gw = gatewayWith(fileService, stubTool("empty.read", true, Mono.empty()));

        List<ToolResult> results = gw.dispatchAll(List.of(
                new ToolGateway.ToolCall("empty.read", Map.of())
        ), ctx);

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError(), "空 Mono 不能变成 null 观察");
        assertTrue(String.valueOf(results.get(0).error()).contains("empty.read"));
    }

    private static ToolGateway gatewayWith(FileService fs, Tool... tools) {
        ToolSource source = new ToolSource() {
            @Override
            public String namespace() {
                return "test";
            }

            @Override
            public List<Tool> tools(String workspaceId, String goal) {
                return List.of(tools);
            }
        };
        return new ToolGateway(fs, List.of(source));
    }

    private static Tool stubTool(String name, boolean readOnly, Mono<ToolResult> outcome) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public ToolAnnotations annotations() {
                return readOnly ? ToolAnnotations.readOnlyTool() : ToolAnnotations.writableTool();
            }

            @Override
            public Mono<ToolResult> execute(ConversationCtx c, Map<String, Object> a) {
                return outcome;
            }
        };
    }
}
