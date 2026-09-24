package cli.headless;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.cli.headless.OutputFormatter;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * headless 三种输出格式的线格式契约。
 *
 * <p>三种格式被下游以不同方式消费（人来读 / {@code jq} 解析 / 逐行实时消费），
 * 所以每一种都必须单独锁定：正文只能出现在 stdout、进度不能污染 stdout、
 * JSON 行的字段名是显式契约而不是序列化副产物。</p>
 */
class OutputFormatterTest {

    private static final String SESSION = "s-1";

    @Test
    @DisplayName("text：正文进 stdout、进度进 stderr —— 保证 `> answer.txt` 只拿到答案")
    void textSplitsStreams() {
        Capturer cap = new Capturer(OutputFormatter.Mode.TEXT);
        cap.formatter.render(AgentEvent.contentDelta(SESSION, "最终答案"));
        cap.formatter.render(AgentEvent.progress(SESSION, "正在思考"));
        cap.formatter.render(AgentEvent.action(SESSION, "c1", "file.write", java.util.Map.of(), null));
        cap.formatter.endOfTurn();

        assertEquals("最终答案" + System.lineSeparator(), cap.stdout());
        assertTrue(cap.stderr().contains("正在思考"), cap.stderr());
        assertTrue(cap.stderr().contains("file.write"), cap.stderr());
    }

    @Test
    @DisplayName("text：stop 事件不打印（结束语义由退出码表达，多打一行会干扰管道消费）")
    void textSuppressesStop() {
        Capturer cap = new Capturer(OutputFormatter.Mode.TEXT);
        cap.formatter.render(AgentEvent.stop(SESSION, "success", "好了"));
        cap.formatter.endOfTurn();
        assertEquals("", cap.stdout());
        assertFalse(cap.stderr().contains("success"));
    }

    @Test
    @DisplayName("json：运行期静默，结束时输出恰好一个 JSON 对象")
    void jsonEmitsSingleObject() {
        Capturer cap = new Capturer(OutputFormatter.Mode.JSON);
        cap.formatter.render(AgentEvent.contentDelta(SESSION, "正文"));
        cap.formatter.render(AgentEvent.progress(SESSION, "进度"));
        cap.formatter.endOfTurn();
        assertEquals("", cap.stdout(), "运行期不得有任何 stdout 输出");

        cap.formatter.printFinal(outcome(RunResult.RunStatus.SUCCESS, "答案"), CliExitCode.SUCCESS, 1200L);
        String json = cap.stdout().trim();
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertEquals(1, json.lines().count(), "应只有一行（单对象而非 NDJSON）");
        assertTrue(json.contains("\"status\":\"success\""), json);
        assertTrue(json.contains("\"exitCode\":0"), json);
        assertTrue(json.contains("\"durationMs\":1200"), json);
    }

    @Test
    @DisplayName("stream-json：每条事件一行 JSON，字段名是显式契约")
    void streamJsonEmitsOneLinePerEvent() {
        Capturer cap = new Capturer(OutputFormatter.Mode.STREAM_JSON);
        cap.formatter.render(AgentEvent.contentDelta(SESSION, "abc"));
        cap.formatter.render(AgentEvent.stop(SESSION, "success", "完成"));
        String out = cap.stdout();
        String[] lines = out.strip().split("\\R");

        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"type\":\"content_delta\""), lines[0]);
        assertTrue(lines[0].contains("\"delta\":\"abc\""), lines[0]);
        assertTrue(lines[0].contains("\"sessionId\":\"s-1\""), lines[0]);
        assertTrue(lines[1].contains("\"type\":\"stop\""), lines[1]);
        assertEquals("", cap.stderr());
    }

    @Test
    @DisplayName("未知格式名回落 text（调用方另有告警，不在此处静默）")
    void unknownModeFallsBackToText() {
        assertEquals(OutputFormatter.Mode.TEXT, OutputFormatter.Mode.parse("yaml"));
        assertEquals(OutputFormatter.Mode.JSON, OutputFormatter.Mode.parse("JSON"));
        assertEquals(OutputFormatter.Mode.STREAM_JSON, OutputFormatter.Mode.parse(" stream-json "));
        assertEquals(OutputFormatter.Mode.TEXT, OutputFormatter.Mode.parse(null));
    }

    @Test
    @DisplayName("失败时 text 模式只在 stderr 补一行摘要（退出码之外给人类可读的原因）")
    void textReportsFailureOnStderr() {
        Capturer cap = new Capturer(OutputFormatter.Mode.TEXT);
        TurnOutcome failed = new TurnOutcome(
                RunResult.of(SESSION).status(RunResult.RunStatus.ERROR).error("模型不可用"),
                new TurnCapture(), null, false, 10L);
        cap.formatter.printFinal(failed, CliExitCode.FAILURE, 10L);

        assertEquals("", cap.stdout(), "失败信息不能写进 stdout（否则会污染管道结果）");
        assertTrue(cap.stderr().contains("模型不可用"), cap.stderr());
        assertTrue(cap.stderr().contains(CliExitCode.describe(CliExitCode.FAILURE)), cap.stderr());
    }

    @Test
    @DisplayName("无运行结果时 json 仍给出结构完整的结果对象（可编程消费）")
    void jsonHandlesNullOutcome() {
        Capturer cap = new Capturer(OutputFormatter.Mode.JSON);
        cap.formatter.printFinal(null, CliExitCode.PENDING_UNRESOLVED, 0L);
        String json = cap.stdout().trim();
        assertTrue(json.contains("\"status\":\"no_result\""), json);
        assertTrue(json.contains("\"exitCode\":5"), json);
    }

    @Test
    @DisplayName("JSON 序列化失败不中断流（退化为 serialize_error 行）")
    void serializeFailureDegradesGracefully() {
        Capturer cap = new Capturer(OutputFormatter.Mode.STREAM_JSON);
        // payload 里放一个 Jackson 无法序列化的对象（自引用）
        Object weird = new Object() {
            @SuppressWarnings("unused")
            public Object self() {
                return this;
            }
        };
        cap.formatter.render(AgentEvent.progress(SESSION, "x"));
        cap.formatter.render(AgentEvent.toolResult(SESSION, "c1", "t", true, "s", weird, null));
        assertEquals(2, cap.stdout().strip().split("\\R").length, "不得因一条事件序列化失败而丢后续事件");
    }

    private static TurnOutcome outcome(RunResult.RunStatus status, String summary) {
        return new TurnOutcome(RunResult.of(SESSION).status(status).summary(summary),
                new TurnCapture(), null, false, 5L);
    }

    /** 捕获 stdout / stderr 的测试壳。 */
    private static final class Capturer {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final OutputFormatter formatter;

        Capturer(OutputFormatter.Mode mode) {
            this.formatter = new OutputFormatter(mode,
                    OutputSink.of(stream(out), Theme.plain()),
                    OutputSink.of(stream(err), Theme.plain()),
                    false);
        }

        String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        private static PrintStream stream(ByteArrayOutputStream target) {
            return new PrintStream(target, true, StandardCharsets.UTF_8);
        }
    }
}
