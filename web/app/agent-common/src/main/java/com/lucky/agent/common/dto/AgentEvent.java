package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一事件结构（契约 §1），Web / CLI 消费同一事件流。
 *
 * <p>字段名与 JSON 契约一致；payload 为动态 Map，禁止通道各自扩展事件类型。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {

    /** 事件契约版本。 */
    public static final String VERSION = "1.0";

    private final String version = VERSION;
    private final AgentEventType type;
    private final String eventId;
    private final String sessionId;
    private final String taskId;
    private final String callId;
    private final String ts;
    private final Map<String, Object> payload;

    private AgentEvent(AgentEventType type, String sessionId, String taskId, String callId,
                       Map<String, Object> payload) {
        this.type = type;
        this.eventId = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.callId = callId;
        this.ts = Instant.now().toString();
        this.payload = payload == null ? new HashMap<>() : payload;
    }

    @JsonProperty("version")
    public String version() {
        return version;
    }

    @JsonProperty("type")
    public String type() {
        return type.jsonValue();
    }

    @JsonProperty("eventId")
    public String eventId() {
        return eventId;
    }

    @JsonProperty("sessionId")
    public String sessionId() {
        return sessionId;
    }

    @JsonProperty("taskId")
    public String taskId() {
        return taskId;
    }

    @JsonProperty("callId")
    public String callId() {
        return callId;
    }

    @JsonProperty("ts")
    public String ts() {
        return ts;
    }

    @JsonProperty("payload")
    public Map<String, Object> payload() {
        return payload;
    }

    // ---------- payload 字段常量 ----------

    public static final String KEY_CONTENT = "content";
    /** 正文流式增量（{@code content_delta} 事件）。 */
    public static final String KEY_DELTA = "delta";
    public static final String KEY_TOOL = "tool";
    public static final String KEY_ARGS = "args";
    public static final String KEY_RISK = "risk";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_OK = "ok";
    public static final String KEY_SUMMARY = "summary";
    public static final String KEY_DATA = "data";
    public static final String KEY_ERROR = "error";
    public static final String KEY_SKILL_ID = "skillId";
    public static final String KEY_MATCH = "match";
    public static final String KEY_SERVER_ID = "serverId";
    public static final String KEY_TASKS = "tasks";
    public static final String KEY_TASK_ID = "taskId";
    public static final String KEY_TITLE = "title";
    public static final String KEY_STATUS = "status";
    public static final String KEY_DONE = "done";
    public static final String KEY_TOTAL = "total";
    public static final String KEY_QUESTION = "question";
    public static final String KEY_STAGE = "stage";
    public static final String KEY_MSG = "msg";
    public static final String KEY_FALLBACK = "fallback";
    public static final String KEY_USED = "used";
    public static final String KEY_MODEL = "model";
    public static final String KEY_WARN = "warn";
    public static final String KEY_REASON = "reason";
    /** 会话标题（stop 事件可选携带，供前端同步列表标题）。 */
    public static final String KEY_SESSION_TITLE = "sessionTitle";
    /** 条件选择的选项列表（options 事件）。 */
    public static final String KEY_OPTIONS = "options";
    /** 是否允许自定义补充（options 事件）。 */
    public static final String KEY_ALLOW_CUSTOM = "allowCustom";
    /** 自定义补充输入框提示（options 事件）。 */
    public static final String KEY_CUSTOM_HINT = "customHint";
    /** 选项挂起超时秒数（options 事件；超时后由后端按推荐项自动继续）。 */
    public static final String KEY_TIMEOUT_SEC = "timeoutSec";

    // ---------- 静态工厂 ----------

    public static AgentEvent thought(String sessionId, String content) {
        return of(AgentEventType.THOUGHT, sessionId, mapOf(KEY_CONTENT, content));
    }

    /** 系统执行进度/状态消息（阶段切换、分析、验证、安全阀等，不占思考计数）。 */
    public static AgentEvent progress(String sessionId, String content) {
        return of(AgentEventType.PROGRESS, sessionId, mapOf(KEY_CONTENT, content));
    }

    /** 最终回复正文流式增量（前端逐字追加到消息内容）。 */
    public static AgentEvent contentDelta(String sessionId, String delta) {
        return of(AgentEventType.CONTENT_DELTA, sessionId, mapOf(KEY_DELTA, delta));
    }

    public static AgentEvent action(String sessionId, String callId, String tool,
                                    Map<String, Object> args, String risk) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_TOOL, tool);
        payload.put(KEY_ARGS, args);
        if (risk != null) {
            payload.put(KEY_RISK, risk);
        }
        return of(AgentEventType.ACTION, sessionId, callId, payload);
    }

    public static AgentEvent toolResult(String sessionId, String callId, String source, boolean ok,
                                        String summary, Object data, String error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_CALL_ID, callId);
        payload.put(KEY_SOURCE, source);
        payload.put(KEY_OK, ok);
        payload.put(KEY_SUMMARY, summary);
        if (data != null) {
            payload.put(KEY_DATA, data);
        }
        if (error != null) {
            payload.put(KEY_ERROR, error);
        }
        return of(AgentEventType.TOOL_RESULT, sessionId, callId, payload);
    }

    public static AgentEvent skillInvoke(String sessionId, String skillId, String match) {
        Map<String, Object> payload = mapOf(KEY_SKILL_ID, skillId, KEY_MATCH, match);
        return of(AgentEventType.SKILL_INVOKE, sessionId, payload);
    }

    public static AgentEvent mcpInvoke(String sessionId, String serverId, String tool, String source) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_SERVER_ID, serverId);
        payload.put(KEY_TOOL, tool);
        payload.put(KEY_SOURCE, source);
        return of(AgentEventType.MCP_INVOKE, sessionId, payload);
    }

    public static AgentEvent taskPlan(String sessionId, List<TaskItem> tasks) {
        List<Map<String, Object>> taskList = tasks.stream()
                .map(t -> mapOf(KEY_TASK_ID, t.taskId(), KEY_TITLE, t.title()))
                .toList();
        return of(AgentEventType.TASK_PLAN, sessionId, mapOf(KEY_TASKS, taskList));
    }

    public static AgentEvent taskProgress(String sessionId, String taskId, TaskProgressStatus status,
                                          int done, int total) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_TASK_ID, taskId);
        payload.put(KEY_STATUS, status.jsonValue());
        payload.put(KEY_DONE, done);
        payload.put(KEY_TOTAL, total);
        return of(AgentEventType.TASK_PROGRESS, sessionId, payload);
    }

    public static AgentEvent ask(String sessionId, String question, String risk) {
        Map<String, Object> payload = mapOf(KEY_QUESTION, question, KEY_RISK, risk);
        return of(AgentEventType.ASK, sessionId, payload);
    }

    /** ASK 携带待确认操作详情（供前端构造确认请求）。 */
    public static AgentEvent ask(String sessionId, String question, String risk, Map<String, Object> op) {
        Map<String, Object> payload = mapOf(KEY_QUESTION, question, KEY_RISK, risk);
        if (op != null) {
            payload.put("op", op);
        }
        return of(AgentEventType.ASK, sessionId, payload);
    }

    /**
     * LLM 条件选择事件：向用户展示编号选项（可含「其他」自定义入口），
     * 前端渲染为选项卡；用户选择后以普通用户消息回传，对话继续。
     *
     * @param sessionId   会话 id
     * @param question    决策问题
     * @param options     选项列表（id/label/detail/recommended）
     * @param allowCustom 是否允许自定义补充
     * @param customHint  自定义输入框占位提示
     * @param timeoutSec  挂起超时秒数；&lt;=0 表示不设超时
     */
    public static AgentEvent options(String sessionId, String question, List<OptionItem> options,
                                     boolean allowCustom, String customHint, long timeoutSec) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_QUESTION, question);
        payload.put(KEY_OPTIONS, options == null ? List.of() : options);
        payload.put(KEY_ALLOW_CUSTOM, allowCustom);
        if (customHint != null && !customHint.isBlank()) {
            payload.put(KEY_CUSTOM_HINT, customHint);
        }
        if (timeoutSec > 0) {
            payload.put(KEY_TIMEOUT_SEC, timeoutSec);
        }
        return of(AgentEventType.OPTIONS, sessionId, payload);
    }

    /** LLM 条件选择事件（默认允许自定义补充，超时由调用方决定）。 */
    public static AgentEvent options(String sessionId, String question, List<OptionItem> options,
                                     long timeoutSec) {
        return options(sessionId, question, options, true, "其他（请补充说明）", timeoutSec);
    }

    public static AgentEvent error(String sessionId, String stage, String callId, String msg, String fallback) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_STAGE, stage);
        if (callId != null) {
            payload.put(KEY_CALL_ID, callId);
        }
        payload.put(KEY_MSG, msg);
        if (fallback != null) {
            payload.put(KEY_FALLBACK, fallback);
        }
        return of(AgentEventType.ERROR, sessionId, callId, payload);
    }

    public static AgentEvent token(String sessionId, long used, long total, String model, Boolean warn) {
        return token(sessionId, used, used, 0, total, model, warn);
    }

    /**
     * token 事件。{@code used} 为本次模型调用的消耗增量（输入+输出），{@code input}/{@code output}
     * 为本次调用输入/输出分计。指标收集器按 {@code used} 累加得到会话级总量，避免重复累计。
     */
    public static AgentEvent token(String sessionId, long used, long input, long output,
                                   long total, String model, Boolean warn) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(KEY_USED, used);
        payload.put("input", input);
        payload.put("output", output);
        payload.put(KEY_TOTAL, total);
        payload.put(KEY_MODEL, model);
        if (warn != null) {
            payload.put(KEY_WARN, warn);
        }
        return of(AgentEventType.TOKEN, sessionId, payload);
    }

    public static AgentEvent stop(String sessionId, String reason, String summary) {
        Map<String, Object> payload = mapOf(KEY_REASON, reason, KEY_SUMMARY, summary);
        return of(AgentEventType.STOP, sessionId, payload);
    }

    /**
     * 带会话标题的 stop 事件：首轮结束后随收尾一并下发（应用 LLM 精简后的标题），
     * 供前端同步会话列表标题，无需额外轮询会话列表。
     */
    public static AgentEvent stop(String sessionId, String reason, String summary, String sessionTitle) {
        Map<String, Object> payload = mapOf(KEY_REASON, reason, KEY_SUMMARY, summary);
        if (sessionTitle != null && !sessionTitle.isBlank()) {
            payload.put(KEY_SESSION_TITLE, sessionTitle);
        }
        return of(AgentEventType.STOP, sessionId, payload);
    }

    private static final String KEY_CALL_ID = "callId";

    private static AgentEvent of(AgentEventType type, String sessionId, Map<String, Object> payload) {
        return new AgentEvent(type, sessionId, null, null, payload);
    }

    private static AgentEvent of(AgentEventType type, String sessionId, String callId,
                                 Map<String, Object> payload) {
        return new AgentEvent(type, sessionId, null, callId, payload);
    }

    private static Map<String, Object> mapOf(String k1, Object v1) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        return m;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /**
     * 复杂任务拆分清单中的单项。
     *
     * @param taskId 任务 ID
     * @param title  任务标题
     */
    public record TaskItem(String taskId, String title) {
    }

    /**
     * 条件选择中的单个选项。
     *
     * @param id          选项标识（一般是 "1"/"2"/"3"，回传时作为用户选择）
     * @param label       选项标题（一句话，显示在按钮上）
     * @param detail      选项说明（可选，展开后显示）
     * @param recommended 是否为模型推荐项（超时未选时优先选中）
     */
    public record OptionItem(String id, String label, String detail, Boolean recommended) {
    }

    /**
     * 任务进度状态枚举，JSON 值为契约定义的小写形式。
     */
    public enum TaskProgressStatus {
        PENDING("pending"),
        RUNNING("running"),
        DONE("done"),
        FAILED("failed"),
        ASK("ask");

        private final String jsonValue;

        TaskProgressStatus(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }
    }
}
