package com.lucky.agent.core.util.engine;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 工具结果事件契约回归：失败必须如实上报（ok=false / error 透传），
 * 防止 SSE、CLI 与 MetricsCollector 把失败记成成功。
 *
 * <p>对应缺陷：旧实现 {@code AgentEvent.toolResult(..., true, ..., null)} 第 4 参恒 true。</p>
 */
class ReactEngineToolResultEventTest {

    private static final int LIMIT = 20000;

    @Test
    void successKeepsOkAndNoError() {
        AgentEvent event = ReactEngine.toolResultEvent("s1", "c1", "file.read",
                ToolResult.ok("content"), "content", LIMIT);

        assertEquals(Boolean.TRUE, event.payload().get(AgentEvent.KEY_OK));
        assertEquals("content", event.payload().get(AgentEvent.KEY_SUMMARY));
        assertFalse(event.payload().containsKey(AgentEvent.KEY_ERROR));
    }

    @Test
    void failureIsReportedAsFailureWithErrorText() {
        AgentEvent event = ReactEngine.toolResultEvent("s1", "c2", "file.write",
                ToolResult.error("权限拒绝：档位不足"), "权限拒绝：档位不足", LIMIT);

        assertEquals(Boolean.FALSE, event.payload().get(AgentEvent.KEY_OK));
        assertEquals("权限拒绝：档位不足", event.payload().get(AgentEvent.KEY_ERROR));
        assertEquals("权限拒绝：档位不足", event.payload().get(AgentEvent.KEY_SUMMARY));
    }

    @Test
    void failureWithoutMessageNeverFallsBackToOkSummary() {
        AgentEvent event = ReactEngine.toolResultEvent("s1", "c3", "shell.exec",
                ToolResult.error(null), null, LIMIT);

        assertEquals(Boolean.FALSE, event.payload().get(AgentEvent.KEY_OK));
        assertEquals("error", event.payload().get(AgentEvent.KEY_SUMMARY),
                "失败且无错误文案时摘要不能回退成 ok");
    }

    @Test
    void longSummaryIsTruncated() {
        String text = "x".repeat(30);
        AgentEvent event = ReactEngine.toolResultEvent("s1", "c4", "file.read",
                ToolResult.ok(text), text, 10);

        assertEquals("xxxxxxxxxx…", event.payload().get(AgentEvent.KEY_SUMMARY));
    }

    @Test
    void suspendedIsNotCountedAsSuccess() {
        // 引擎侧 suspended 提前转 ask 事件并 return；这里守「万一被误传也不会标成功」。
        AgentEvent event = ReactEngine.toolResultEvent("s1", "c5", "file.delete",
                ToolResult.suspended("需要确认"), "需要确认", LIMIT);

        assertEquals(Boolean.FALSE, event.payload().get(AgentEvent.KEY_OK));
    }
}
