package com.lucky.agent.common.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentEvent 类型枚举，与契约定义 §1 的 JSON type 字段一一对应。
 *
 * <p>Web / CLI 消费同一事件流，禁止通道各自扩展事件类型。</p>
 */
public enum AgentEventType {

    /** 推理思考过程（一次 LLM 调用产出的思考 = 一条），payload: {@code content}。 */
    THOUGHT("thought"),

    /** 系统执行进度/状态消息（阶段切换、分析判定、安全阀等，不属 LLM 思考），payload: {@code content}。 */
    PROGRESS("progress"),

    /** 最终回复正文流式增量，payload: {@code delta}。 */
    CONTENT_DELTA("content_delta"),

    /** 工具调用发起，payload: {@code tool, args, risk}。 */
    ACTION("action"),

    /** 工具调用结果，payload: {@code callId, source, ok, summary, data?, error?}。 */
    TOOL_RESULT("tool_result"),

    /** Skill 调用，payload: {@code skillId, match}。 */
    SKILL_INVOKE("skill_invoke"),

    /** MCP 调用，payload: {@code serverId, tool, source}。 */
    MCP_INVOKE("mcp_invoke"),

    /** 复杂任务拆分计划，payload: {@code tasks:[{taskId,title}]}。 */
    TASK_PLAN("task_plan"),

    /** 任务进度，payload: {@code taskId, status, done, total}。 */
    TASK_PROGRESS("task_progress"),

    /** 挂起提问，payload: {@code question, risk}。 */
    ASK("ask"),

    /**
     * LLM 条件选择（多选项 + 自定义补充），payload:
     * {@code question, options:[{id,label,detail?,recommended?}], allowCustom, customHint?, timeoutSec?}。
     * 与 {@link #ASK} 的区别：ASK 是「允许/拒绝」二元确认，OPTIONS 是「多选一」决策。
     */
    OPTIONS("options"),

    /** 错误，payload: {@code stage, callId?, msg, fallback}。 */
    ERROR("error"),

    /** token 用量，payload: {@code used, total, model, warn?}。 */
    TOKEN("token"),

    /** 结束，payload: {@code reason, summary?}。 */
    STOP("stop");

    private final String jsonValue;

    /** jsonValue → 枚举 的只读索引（枚举常量在类初始化后不再变化）。 */
    private static final Map<String, AgentEventType> BY_JSON_VALUE;

    static {
        Map<String, AgentEventType> index = new LinkedHashMap<>();
        for (AgentEventType t : values()) {
            index.put(t.jsonValue, t);
        }
        BY_JSON_VALUE = Collections.unmodifiableMap(index);
    }

    AgentEventType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }

    /**
     * 按 JSON 值反查枚举类型。
     *
     * <p><b>为什么需要这个方法</b>：{@link AgentEvent#type()} 返回的是 {@code jsonValue()} 字符串，
     * 而 Java 的 {@code switch} 标签必须是编译期常量，无法写成
     * {@code case AgentEventType.THOUGHT.jsonValue() -> ...}。消费端若改用字符串字面量做分支，
     * 就会在契约新增事件类型时静默漏渲染（CLI 早期实现即因此漏掉 4 类事件，其中
     * {@code options} 被吞会导致会话直接卡死）。故统一经本方法反查后再 switch。</p>
     *
     * @param jsonValue 事件 JSON type 值
     * @return 对应枚举；未知值返回 {@code null}，由调用方走兜底渲染（<b>不得静默丢弃</b>）
     */
    public static AgentEventType fromJsonValue(String jsonValue) {
        return jsonValue == null ? null : BY_JSON_VALUE.get(jsonValue);
    }
}
