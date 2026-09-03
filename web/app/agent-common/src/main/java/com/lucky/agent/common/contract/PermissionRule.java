package com.lucky.agent.common.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 权限规则（契约 §3），由 agent-workspace 或用户配置产生，同步到执行臂本地重新评估。
 *
 * <p>裁决语义：规则按 {@code priority} 升序执行，DENY 永远胜出（deny-wins）；
 * 无规则命中时按工作区级别走 ASK 或 ALLOW。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionRule {

    public static final String VERSION = "1.0";

    private final String version = VERSION;
    private String id;
    private int priority = 100;
    private RuleType type = RuleType.PATH;
    private Matcher matcher;
    private RuleAction action = RuleAction.ASK;
    private String reason;

    /** 规则类型。 */
    public enum RuleType {
        PATH("PATH"),
        COMMAND("COMMAND");

        private final String code;

        RuleType(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 规则动作。 */
    public enum RuleAction {
        ALLOW("ALLOW"),
        DENY("DENY"),
        ASK("ASK");

        private final String code;

        RuleAction(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 路径锚定语义：{@code //abs} 文件系统绝对路径、{@code home} 用户目录、{@code project} 工作区根、{@code relative} 当前目录。 */
    public enum Anchor {
        ABS("//abs"),
        HOME("home"),
        PROJECT("project"),
        RELATIVE("relative");

        private final String code;

        Anchor(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Anchor fromCode(String code) {
            for (Anchor a : values()) {
                if (a.code.equalsIgnoreCase(code)) {
                    return a;
                }
            }
            return PROJECT;
        }
    }

    /**
     * 匹配器。
     *
     * @param pattern glob 或 regex 模式
     * @param anchor  路径锚定语义
     */
    public record Matcher(String pattern, String anchor) {
        public Matcher {
            Objects.requireNonNull(pattern, "pattern 不能为空");
        }
    }

    @JsonProperty("version")
    public String version() {
        return version;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("id")
    public PermissionRule id(String id) {
        this.id = id;
        return this;
    }

    @JsonProperty("priority")
    public int priority() {
        return priority;
    }

    @JsonProperty("priority")
    public PermissionRule priority(int priority) {
        this.priority = priority;
        return this;
    }

    @JsonProperty("type")
    public RuleType type() {
        return type;
    }

    @JsonProperty("type")
    public PermissionRule type(RuleType type) {
        this.type = type;
        return this;
    }

    @JsonProperty("matcher")
    public Matcher matcher() {
        return matcher;
    }

    @JsonProperty("matcher")
    public PermissionRule matcher(Matcher matcher) {
        this.matcher = matcher;
        return this;
    }

    @JsonProperty("action")
    public RuleAction action() {
        return action;
    }

    @JsonProperty("action")
    public PermissionRule action(RuleAction action) {
        this.action = action;
        return this;
    }

    @JsonProperty("reason")
    public String reason() {
        return reason;
    }

    @JsonProperty("reason")
    public PermissionRule reason(String reason) {
        this.reason = reason;
        return this;
    }
}
