package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.workflow.domain.enums.TriggerType;

/**
 * 触发器定义。
 * <ul>
 *   <li>MANUAL：仅手动触发。</li>
 *   <li>INTERVAL：按 expression（毫秒数）周期触发。</li>
 *   <li>CRON：未来扩展（完整 cron 解析）。</li>
 * </ul>
 */
public record TriggerDef(
        @JsonProperty("type") TriggerType type,
        @JsonProperty("expression") String expression,
        @JsonProperty("enabled") boolean enabled) {

    public TriggerDef {
        type = (type == null) ? TriggerType.MANUAL : type;
    }

    public static TriggerDef manual() {
        return new TriggerDef(TriggerType.MANUAL, null, true);
    }
}
