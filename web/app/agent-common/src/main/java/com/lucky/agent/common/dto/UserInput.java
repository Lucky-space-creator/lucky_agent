package com.lucky.agent.common.dto;

import java.util.Map;

/**
 * 用户输入（通道 → 内核）。
 *
 * @param content 用户提示词文本
 * @param extra   附加信息（如需要挂起的 ASK 回复）
 */
public record UserInput(String content, Map<String, Object> extra) {

    public static UserInput of(String content) {
        return new UserInput(content, Map.of());
    }

    public static UserInput of(String content, Map<String, Object> extra) {
        return new UserInput(content, extra == null ? Map.of() : extra);
    }
}
