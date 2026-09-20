package com.lucky.agent.workflow.engine;

/**
 * 输入映射：将上游/全局作用域中的某个变量，映射到当前节点的输入参数名。
 * <p>source 支持点路径表达式，例如 {@code "global.user.id"} 或 {@code "nodeA.result"}；
 * 若 source 为带引号的字面量（如 {@code "'default'"}）则直接作为字面量。</p>
 *
 * @param source 源表达式（在上游作用域中解析）
 * @param target 目标参数名（写入当前节点输入作用域）
 */
public record InputMapping(String source, String target) {
    public InputMapping {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("InputMapping.target 不能为空");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("InputMapping.source 不能为空");
        }
    }
}
