package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.common.constant.PermissionDecision;
import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 生命周期 Hook 事件（契约 §2）。
 *
 * <p>输入字段由触发方填充；输出字段（decision/decisionReason/systemMessage/modifiedInput）
 * 由 Hook 监听器在裁决时填充。多个 Hook 按注册顺序执行，任一 DENY 立即阻断（deny-wins）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class HookEvent {

    /** 契约版本。 */
    public static final String VERSION = "1.0";

    @JsonProperty("version")
    private final String version = VERSION;
    @JsonProperty("hookId")
    private final String hookId;
    @JsonProperty("hookEventName")
    private final HookEventName hookEventName;
    @JsonProperty("sessionId")
    private final String sessionId;
    @JsonProperty("cwd")
    private String cwd;
    @JsonProperty("toolName")
    private String toolName;
    @JsonProperty("toolInput")
    private Map<String, Object> toolInput;
    @JsonProperty("permissionMode")
    private String permissionMode;
    @JsonProperty("ts")
    private final String ts;

    @JsonProperty("decision")
    private PermissionDecision decision;
    @JsonProperty("decisionReason")
    private String decisionReason;
    @JsonProperty("systemMessage")
    private String systemMessage;
    @JsonProperty("modifiedInput")
    private Map<String, Object> modifiedInput;

    public HookEvent(HookEventName hookEventName, String sessionId) {
        this.hookId = UUID.randomUUID().toString();
        this.hookEventName = hookEventName;
        this.sessionId = sessionId;
        this.ts = Instant.now().toString();
    }

    public HookEvent toolInput(Map<String, Object> toolInput) {
        this.toolInput = toolInput == null ? new HashMap<>() : toolInput;
        return this;
    }

    /** 是否已被 DENY 阻断（deny-wins）。 */
    public boolean isDenied() {
        return decision == PermissionDecision.DENY;
    }
}
