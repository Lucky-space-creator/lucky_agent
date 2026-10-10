package com.lucky.agent.cli;

import com.lucky.agent.cli.CliTurnExecutor;
import com.lucky.agent.cli.CliTurnLoop;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单次请求的内核往返循环：步数上限与「同操作去重」两条防跑飞约束。
 *
 * <p>为什么必须用真实事件打通：{@code isAsk} 的判定依赖 {@code stop(reason=ask)} 与
 * {@code ask(op=…)} 两个事件的组合形状，只断言「调了几次」会漏掉「形状不匹配 → 分支根本没进」
 * 这类静默失效。故此处构造真实 {@link TurnCapture} 现场。</p>
 */
class CliTurnLoopTest {

    private static final SessionRef REF = new SessionRef("sess-1", "local-user", "ws-1");

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final OutputSink out =
            OutputSink.of(new PrintStream(buf, true, StandardCharsets.UTF_8), Theme.plain());

    @Test
    @DisplayName("续跑步数恰好为 MAX_STEPS（上限在发起下一次往返之前判定，P1-3 回归）")
    void stepLimitIsExact() {
        CliTurnExecutor executor = mock(CliTurnExecutor.class);
        AtomicInteger n = new AtomicInteger();
        // 每次返回不同路径的 op：避免触发「同操作去重」而提前结束
        when(executor.execute(any(), any(), any()))
                .thenAnswer(inv -> askOutcome("WRITE", "f" + n.incrementAndGet() + ".txt"));

        CliTurnLoop loop = new CliTurnLoop(executor, autoApprove(), out, null);
        CliTurnLoop.Result r = loop.run(REF, "开始", Map.of(), null);

        assertEquals(CliTurnLoop.MAX_STEPS, r.steps(), "必须恰好执行上限步，不得 off-by-one");
        verify(executor, times(CliTurnLoop.MAX_STEPS)).execute(any(), any(), any());
        assertTrue(text().contains("最大往返步数"), "达到上限应给出提示：" + text());
    }

    @Test
    @DisplayName("同一操作被重复询问即停止（防内核放行键失配导致的无限循环）")
    void duplicateOperationStops() {
        CliTurnExecutor executor = mock(CliTurnExecutor.class);
        when(executor.execute(any(), any(), any())).thenAnswer(inv -> askOutcome("WRITE", "same.txt"));

        CliTurnLoop loop = new CliTurnLoop(executor, autoApprove(), out, null);
        CliTurnLoop.Result r = loop.run(REF, "开始", Map.of(), null);

        assertEquals(2, r.steps(), "第 1 步放行、第 2 步同操作 → 立即停止");
        assertTrue(text().contains("重复询问"), "应打印诊断而非静默卡死：" + text());
    }

    @Test
    @DisplayName("非 ASK 的正常收尾只跑一步即返回")
    void normalOutcomeStopsAfterOneStep() {
        CliTurnExecutor executor = mock(CliTurnExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(plainOutcome());

        CliTurnLoop loop = new CliTurnLoop(executor, autoApprove(), out, null);
        CliTurnLoop.Result r = loop.run(REF, "开始", Map.of(), null);

        assertEquals(1, r.steps());
        verify(executor, times(1)).execute(any(), any(), any());
    }

    /** 自动放行的裁决器：让 ASK 分支直接通过，聚焦循环控制本身。 */
    private ApprovalHandler autoApprove() {
        return new ApprovalHandler(out, null, null, null, true);
    }

    private static TurnOutcome askOutcome(String opType, String path) {
        TurnCapture cap = new TurnCapture();
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("opType", opType);
        op.put("path", path);
        op.put("args", Map.of());
        cap.add(AgentEvent.ask("sess-1", "确认？", "HIGH", op));
        cap.add(AgentEvent.stop("sess-1", "ask", "等待确认"));
        return new TurnOutcome(null, cap, null, false, 1L);
    }

    private static TurnOutcome plainOutcome() {
        TurnCapture cap = new TurnCapture();
        cap.add(AgentEvent.stop("sess-1", "done", "完成"));
        return new TurnOutcome(null, cap, null, false, 1L);
    }

    private String text() {
        return buf.toString(StandardCharsets.UTF_8);
    }
}