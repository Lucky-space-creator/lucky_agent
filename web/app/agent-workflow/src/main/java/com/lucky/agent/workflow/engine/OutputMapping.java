package com.lucky.agent.workflow.engine;

/**
 * 输出映射：将当前节点的输出字段，写回到工作流全局作用域中供后续节点使用。
 *
 * @param source 源字段（在当前节点输出作用域中解析，支持点路径）
 * @param target 目标变量名（写入工作流全局作用域）
 */
public record OutputMapping(String source, String target) {
    public OutputMapping {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("OutputMapping.target 不能为空");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("OutputMapping.source 不能为空");
        }
    }
}
