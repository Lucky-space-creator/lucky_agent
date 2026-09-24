package com.lucky.agent.cli.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.channel.EventRenderer;
import com.lucky.agent.cli.channel.EventSink;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.AgentEventType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * headless 输出格式。
 *
 * <p><b>三种格式的职责分界</b></p>
 * <table>
 *   <tr><td>{@code text}</td><td>正文 → stdout（流式）；进度/工具/错误 → stderr。
 *       目标是「可管道」：{@code lucky -p "..." > answer.txt} 只拿到答案</td></tr>
 *   <tr><td>{@code json}</td><td>运行期静默，结束时输出<b>一个</b> JSON 对象。
 *       目标是「可被 {@code jq} 直接消费」</td></tr>
 *   <tr><td>{@code stream-json}</td><td>每条事件一行 JSON（NDJSON），实时输出。
 *       目标是「实时消费过程而不丢失结构化信息」</td></tr>
 * </table>
 *
 * <p>流式格式的 JSON 由本类手工组键（而不是直接序列化 {@code AgentEvent}）：
 * 事件对象的 getter 是 {@code type()} / {@code sessionId()} 这种短名，
 * 依赖 Jackson 的命名推断会产出不可控的字段名。手工组键让<b>线格式成为显式契约</b>。</p>
 */
public final class OutputFormatter implements EventSink {

    public enum Mode {
        TEXT,
        JSON,
        STREAM_JSON;

        /** 解析格式名（未知值回落到 TEXT，并可通过返回值是否为 null 判断是否需要提示）。 */
        public static Mode parse(String raw) {
            if (raw == null) {
                return TEXT;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "json" -> JSON;
                case "stream-json" -> STREAM_JSON;
                default -> TEXT;
            };
        }
    }

    private final Mode mode;
    private final OutputSink stdout;
    private final OutputSink stderr;
    private final boolean showThinking;
    private final ObjectMapper mapper = new ObjectMapper();

    /** TEXT 模式下正文是否停在未换行状态。 */
    private boolean midLine;

    /** TEXT 模式下用于渲染进度行的渲染器（指向 stderr，惰性创建）。 */
    private EventRenderer progressRenderer;

    public OutputFormatter(Mode mode, OutputSink stdout, OutputSink stderr, boolean showThinking) {
        this.mode = mode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.showThinking = showThinking;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public void render(AgentEvent event) {
        if (event == null) {
            return;
        }
        switch (mode) {
            case TEXT -> renderText(event);
            case STREAM_JSON -> stdout.println(toJson(eventLine(event)));
            case JSON -> { /* 运行期刻意静默：只输出单个结果对象 */ }
        }
    }

    @Override
    public void endOfTurn() {
        if (mode == Mode.TEXT && midLine) {
            stdout.println();
            midLine = false;
        }
        stdout.flush();
        stderr.flush();
    }

    private void renderText(AgentEvent event) {
        AgentEventType type = AgentEventType.fromJsonValue(event.type());
        if (type == null) {
            stderr.println("[未识别事件 " + event.type() + "] " + event.payload());
            return;
        }
        if (type == AgentEventType.CONTENT_DELTA) {
            Object delta = event.payload().get(AgentEvent.KEY_DELTA);
            if (delta != null) {
                stdout.print(String.valueOf(delta));
                midLine = true;
            }
            return;
        }
        // 其余事件走交互式渲染器的同一套格式，但写 stderr —— 复用格式定义，避免两处文案漂移
        if (type == AgentEventType.THOUGHT || type == AgentEventType.PROGRESS
                || type == AgentEventType.ACTION || type == AgentEventType.TOOL_RESULT
                || type == AgentEventType.SKILL_INVOKE || type == AgentEventType.MCP_INVOKE
                || type == AgentEventType.TASK_PLAN || type == AgentEventType.TASK_PROGRESS
                || type == AgentEventType.ERROR || type == AgentEventType.ASK
                || type == AgentEventType.OPTIONS || type == AgentEventType.TOKEN) {
            if (midLine) {
                stdout.println();
                midLine = false;
            }
            progressRenderer().render(event);
        }
        // STOP 不输出：headless 的「结束」由进程退出码表达，多打一行反而干扰管道消费
    }

    /** 进度渲染器（指向 stderr 的独立实例，避免与 stdout 的行内状态互相干扰）。 */
    private EventRenderer progressRenderer() {
        if (progressRenderer == null) {
            progressRenderer = new EventRenderer(stderr, showThinking);
        }
        return progressRenderer;
    }

    /**
     * 输出最终结果。
     *
     * @param outcome    最后一轮产出（可为 null）
     * @param exitCode   退出码（会写入 json / stream-json 的末尾对象）
     * @param durationMs 全程耗时
     */
    public void printFinal(TurnOutcome outcome, int exitCode, long durationMs) {
        if (mode == Mode.TEXT) {
            endOfTurn();
            if (exitCode != CliExitCode.SUCCESS) {
                stderr.println("[" + exitCode + " " + CliExitCode.describe(exitCode) + "] "
                        + describeFailure(outcome));
            }
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", outcome == null || outcome.result() == null
                ? null : outcome.result().sessionId());
        result.put("status", statusOf(outcome));
        result.put("summary", textOf(outcome));
        result.put("error", outcome == null ? "无运行结果" : outcome.errorText());
        result.put("tokenUsed", outcome == null || outcome.result() == null
                ? 0L : outcome.result().tokenUsed());
        result.put("model", outcome == null || outcome.result() == null
                ? null : outcome.result().model());
        result.put("turnTokens", outcome == null || outcome.capture() == null
                ? 0L : outcome.capture().tokenUsed());
        result.put("durationMs", durationMs);
        result.put("exitCode", exitCode);
        stdout.println(toJson(result));
    }

    private static String describeFailure(TurnOutcome outcome) {
        if (outcome == null) {
            return "无运行结果";
        }
        String err = outcome.errorText();
        if (err != null && !err.isBlank()) {
            return err;
        }
        if (outcome.timedOut()) {
            return "等待超时";
        }
        return "本轮未正常结束";
    }

    private static String statusOf(TurnOutcome outcome) {
        if (outcome == null || outcome.result() == null) {
            return "no_result";
        }
        return outcome.result().status() == null ? "unknown" : outcome.result().status().code();
    }

    /** 最终正文：优先内核 summary（与落盘一致），退化为事件流拼接。 */
    private static String textOf(TurnOutcome outcome) {
        if (outcome == null || outcome.result() == null) {
            return null;
        }
        String summary = outcome.result().summary();
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return outcome.capture() == null ? null : outcome.capture().finalText();
    }

    private Map<String, Object> eventLine(AgentEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", event.type());
        line.put("sessionId", event.sessionId());
        line.put("payload", event.payload());
        return line;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            // 事件 payload 里出现不可序列化对象时不能整轮失败：退化为文本描述，保证流不中断
            return "{\"type\":\"serialize_error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
