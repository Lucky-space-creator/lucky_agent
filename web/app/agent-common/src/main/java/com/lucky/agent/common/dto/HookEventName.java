package com.lucky.agent.common.dto;

/**
 * Hook 事件名枚举（契约 §2），固定事件名禁止自行扩展。
 */
public enum HookEventName {

    /** 会话开始。 */
    SESSION_START("SessionStart"),

    /** 用户提交提示词。 */
    USER_PROMPT_SUBMIT("UserPromptSubmit"),

    /** 工具调用前。 */
    PRE_TOOL_USE("PreToolUse"),

    /** 工具调用后。 */
    POST_TOOL_USE("PostToolUse"),

    /** 上下文压缩前。 */
    PRE_COMPACT("PreCompact"),

    /** 上下文压缩后。 */
    POST_COMPACT("PostCompact"),

    /** 子代理开始。 */
    SUBAGENT_START("SubagentStart"),

    /** 子代理结束。 */
    SUBAGENT_STOP("SubagentStop"),

    /** 运行结束。 */
    STOP("Stop");

    private final String eventName;

    HookEventName(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
