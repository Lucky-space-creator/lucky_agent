package com.lucky.agent.core.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.LifecycleHook;
import com.lucky.agent.common.dto.HookEvent;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 外部 Hook 基类：封装配置过滤、事件名匹配与外部响应决策解析。
 * <p>子类实现 {@link #invoke(HookEvent)} 完成具体传输（Shell/Webhook/MCP）；
 * 外部响应按契约返回 JSON 片段，本类解析 decision / decisionReason / systemMessage / modifiedInput。</p>
 */
@Slf4j
public abstract class ExternalHook implements LifecycleHook {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected final ExternalHookConfig config;

    protected ExternalHook(ExternalHookConfig config) {
        this.config = config;
    }

    public String id() {
        return config.id();
    }

    public ExternalHookConfig config() {
        return config;
    }

    @Override
    public int order() {
        return config.order();
    }

    @Override
    public HookEvent onEvent(HookEvent event) {
        // 用契约事件名（PreToolUse 等）匹配配置，而非枚举常量名（PRE_TOOL_USE）
        if (config.enabled() && config.matches(event.hookEventName().eventName())) {
            return invoke(event);
        }
        return event;
    }

    /** 实际调用外部系统并返回（可能被修改的）事件；异常由本方法兜底按放行处理。 */
    protected abstract HookEvent invoke(HookEvent event);

    /**
     * 解析外部响应 JSON 并应用裁决。
     *
     * <p>响应形如：{@code {"decision":"ALLOW|DENY","decisionReason":"...","systemMessage":"...","modifiedInput":{...}}}；
     * 解析失败/缺字段按放行处理，不影响原事件。返回同一事件便于链式调用。</p>
     */
    protected HookEvent applyDecision(HookEvent event, String body) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            JsonNode decision = node.get("decision");
            if (decision != null && !decision.isNull()) {
                event.decision("DENY".equalsIgnoreCase(decision.asText())
                        ? PermissionDecision.DENY : PermissionDecision.ALLOW);
            }
            if (node.hasNonNull("decisionReason")) {
                event.decisionReason(node.get("decisionReason").asText());
            }
            if (node.hasNonNull("systemMessage")) {
                event.systemMessage(node.get("systemMessage").asText());
            }
            if (node.hasNonNull("modifiedInput")) {
                event.modifiedInput(OBJECT_MAPPER.convertValue(node.get("modifiedInput"), Map.class));
            }
        } catch (Exception e) {
            log.warn("外部 Hook 响应解析失败（按放行处理）：{}", config.name(), e);
        }
        return event;
    }
}
