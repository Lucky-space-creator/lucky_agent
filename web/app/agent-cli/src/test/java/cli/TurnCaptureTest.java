package cli;

import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.AgentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单轮事件采集。
 *
 * <p>重点在两处：<b>授权操作必须原样取出</b>（P0-2 修复的依托）、
 * <b>正文/ token / 选项</b> 的聚合口径与内核事件字段一致。</p>
 */
class TurnCaptureTest {

    private static final String SESSION = "s-1";

    @Test
    @DisplayName("ask 事件里的 op 必须原样返回（这是「授权所见即所得」的实现基础）")
    void pendingAskOpIsVerbatim() {
        TurnCapture c = new TurnCapture();
        Map<String, Object> op = Map.of(
                "opType", "DELETE",
                "path", "src/Main.java",
                "args", Map.of("recursive", false));
        c.add(AgentEvent.ask(SESSION, "确认删除？", "HIGH", op));

        Map<String, Object> got = c.pendingAskOp();
        assertSame(op, got, "必须是同一个对象引用，不能重建一个「看起来一样」的 Map");
        assertEquals("DELETE", got.get("opType"));
        assertEquals("src/Main.java", got.get("path"));
    }

    @Test
    @DisplayName("多次 ask 取最后一次（引擎可能在同轮内先后问两件事）")
    void pendingAskOpKeepsLatest() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.ask(SESSION, "第一次", "HIGH", Map.of("opType", "WRITE", "path", "a.txt")));
        c.add(AgentEvent.ask(SESSION, "第二次", "HIGH", Map.of("opType", "DELETE", "path", "b.txt")));
        assertEquals("b.txt", c.pendingAskOp().get("path"));
        assertEquals("第二次", c.askQuestion());
    }

    @Test
    @DisplayName("ask 未携带 op → null（调用方据此拒绝，而不是猜一个操作）")
    void pendingAskOpNullWhenMissing() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.ask(SESSION, "要不要继续？", "HIGH"));
        assertNull(c.pendingAskOp());
        assertEquals("要不要继续？", c.askQuestion());
    }

    @Test
    @DisplayName("op 为空 Map 同样视为缺失（空 Map 无法构成有效授权）")
    void pendingAskOpNullForEmptyOp() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.ask(SESSION, "?", "HIGH", Map.of()));
        assertNull(c.pendingAskOp());
    }

    @Test
    @DisplayName("正文由 content_delta 顺序拼接")
    void finalTextConcatenatesDeltas() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.contentDelta(SESSION, "你好"));
        c.add(AgentEvent.progress(SESSION, "正在处理"));
        c.add(AgentEvent.contentDelta(SESSION, "，世界"));
        assertEquals("你好，世界", c.finalText());
    }

    @Test
    @DisplayName("token 取各次上报的 used 之和，total 取最后一次的会话累计")
    void tokenAggregation() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.token(SESSION, 100, 100, "m1", false));
        c.add(AgentEvent.token(SESSION, 40, 140, "m1", false));
        assertEquals(140L, c.tokenUsed());
        assertEquals(140L, c.totalTokens());
        assertEquals("m1", c.model());
    }

    @Test
    @DisplayName("options 事件被完整取出（终端据此渲染可选项）")
    void optionsExtracted() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.options(SESSION, "选哪个方案？",
                List.of(new AgentEvent.OptionItem("1", "方案甲", "最快", true),
                        new AgentEvent.OptionItem("2", "方案乙", "最稳", false)),
                true, "也可以直接说你的想法", 300));
        List<Map<String, Object>> options = c.options();
        assertEquals(2, options.size());
        assertEquals("方案甲", options.get(0).get("label"));
    }

    @Test
    @DisplayName("终态与停止原因识别")
    void terminalState() {
        TurnCapture c = new TurnCapture();
        assertFalse(c.sawTerminal());
        c.add(AgentEvent.stop(SESSION, "ask", "等待确认"));
        assertTrue(c.sawTerminal());
        assertEquals("ask", c.stopReason());
    }

    @Test
    @DisplayName("错误事件取首条（后续错误多为级联，首条最有信息量）")
    void firstErrorMessage() {
        TurnCapture c = new TurnCapture();
        c.add(AgentEvent.error(SESSION, "model", null, "主模型不可用", "回退"));
        c.add(AgentEvent.error(SESSION, "conversation", null, "级联失败", null));
        assertTrue(c.sawError());
        assertEquals("主模型不可用", c.errorMessage());
    }

    @Test
    @DisplayName("事件的 type() 与 AgentEventType 的 jsonValue 口径一致（漏一处就会静默丢事件）")
    void typeValueMatchesEnum() {
        assertEquals(AgentEventType.ASK.jsonValue(), AgentEvent.ask(SESSION, "q", "HIGH").type());
        assertEquals(AgentEventType.CONTENT_DELTA.jsonValue(), AgentEvent.contentDelta(SESSION, "x").type());
        assertEquals(AgentEventType.OPTIONS.jsonValue(),
                AgentEvent.options(SESSION, "q", List.of(), 60).type());
    }
}
