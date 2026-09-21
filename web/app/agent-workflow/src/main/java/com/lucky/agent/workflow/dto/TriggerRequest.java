package com.lucky.agent.workflow.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.workflow.domain.enums.RunMode;

import java.util.Locale;
import java.util.Map;

/**
 * 触发工作流请求体。
 *
 * <p><b>为何 {@code mode} 是 {@code String} 而非 {@link RunMode}：</b>Jackson 默认按枚举名
 * <b>大小写敏感</b>反序列化，请求体里写小写 {@code "sync"} 会抛
 * {@code InvalidFormatException} → 被包装为 {@code ServerWebInputException} →
 * HTTP 400，且回传的 reason 是 {@code "Failed to read HTTP message"} 这类无信息量的框架措辞
 * （前端只能弹一串英文黑话）。这里改为「字符串入参 + 在 record 内自解析」，
 * 让本接口对小写/带空白等常见书写习惯保持宽容，解析失败时给出明确的中文提示。</p>
 *
 * @param variables 初始变量
 * @param mode      执行模式字符串（大小写不敏感，空取默认 SYNC）
 */
public record TriggerRequest(Map<String, Object> variables, String mode) {

    @JsonCreator
    public TriggerRequest(@JsonProperty("variables") Map<String, Object> variables,
                          @JsonProperty("mode") String mode) {
        this.variables = (variables == null) ? Map.of() : variables;
        this.mode = (mode == null || mode.isBlank()) ? RunMode.SYNC.name() : mode.trim();
    }

    /** 解析为执行模式枚举；非法取值给出包含允许值的可读错误（由 GlobalExceptionHandler 映射为 400）。 */
    public RunMode resolveMode() {
        String upper = mode.toUpperCase(Locale.ROOT);
        for (RunMode m : RunMode.values()) {
            if (m.name().equals(upper)) {
                return m;
            }
        }
        throw new IllegalArgumentException("执行模式取值非法: '" + mode + "'，允许值: SYNC / ASYNC");
    }

    public static TriggerRequest empty() {
        return new TriggerRequest(Map.of(), RunMode.SYNC.name());
    }
}
