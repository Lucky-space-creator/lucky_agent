package com.lucky.agent.common.dto;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用（模型发起 → 网关分发）。
 *
 * @param callId 调用 ID，用于把「发起 — 结果」串成一条时间线
 * @param name   工具名
 * @param args   入参
 */
public record ToolCall(String callId, String name, Map<String, Object> args) {

    public static ToolCall of(String name, Map<String, Object> args) {
        return new ToolCall(UUID.randomUUID().toString(), name, args == null ? new HashMap<>() : args);
    }
}
