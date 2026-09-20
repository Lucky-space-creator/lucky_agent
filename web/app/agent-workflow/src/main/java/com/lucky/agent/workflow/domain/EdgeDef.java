package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 流程边（连接器）：连接 source 节点到 target 节点。
 * <p>{@code condition} 为可选的条件表达式（见 {@code ConditionEvaluator}）：
 * 为空表示无条件；非空时仅当表达式对当前全局作用域求值结果为 true 才走此边。
 * 条件边天然实现了「分支 / 条件触发」语义。</p>
 */
public record EdgeDef(
        @JsonProperty("id") String id,
        @JsonProperty("source") String source,
        @JsonProperty("target") String target,
        @JsonProperty("condition") String condition,
        @JsonProperty("label") String label) {

    public EdgeDef {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            throw new IllegalArgumentException("EdgeDef 的 source/target 不能为空");
        }
        if (id == null || id.isBlank()) {
            id = source + "->" + target;
        }
    }

    /** 便捷构造：无 label。 */
    public EdgeDef(String id, String source, String target, String condition) {
        this(id, source, target, condition, null);
    }

    /** 是否为条件边。 */
    public boolean isConditional() {
        return condition != null && !condition.isBlank();
    }
}
