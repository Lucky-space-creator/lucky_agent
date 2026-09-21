package com.lucky.agent.common.dto;

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

    AgentEventType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
