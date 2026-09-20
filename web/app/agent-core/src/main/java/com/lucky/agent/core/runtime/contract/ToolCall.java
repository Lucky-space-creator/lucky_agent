package com.lucky.agent.core.runtime.contract;

import java.util.Map;
import java.util.UUID;

/**
 * 工具调用请求（LLM 决策产出的结构化调用）。
 *
 * @param id          调用 ID
 * @param name        工具名
 * @param arguments   参数
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = (arguments == null) ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(UUID.randomUUID().toString().substring(0, 8), name, arguments);
    }

    public String arg(String key) {
        Object v = arguments.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
