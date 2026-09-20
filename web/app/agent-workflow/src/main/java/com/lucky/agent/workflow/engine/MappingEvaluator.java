package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 连接器求值器：负责输入/输出映射的解析与变量作用域转换。
 * <p>source/target 均为「点路径」表达式，例如 {@code a.b.0.c}：逐段在嵌套 Map/List 中定位。
 * 带引号的 source 视为字面量（如 {@code "'fallback'"}）。</p>
 */
public class MappingEvaluator {

    /**
     * 将节点输出的相关字段，按 OutputMapping 写回全局作用域。
     */
    public void applyOutputs(WorkflowNodeType nodeType,
                             List<OutputMapping> outputs,
                             VariableScope nodeOutput,
                             VariableScope global) {
        if (outputs == null || outputs.isEmpty()) {
            // 默认行为：若节点无显式输出映射，则把整个节点输出作为以节点 id 命名的变量回写（由调用方传入节点命名空间）
            return;
        }
        for (OutputMapping m : outputs) {
            Object value = resolve(m.source(), nodeOutput);
            global.set(m.target(), value);
        }
    }

    /**
     * 根据 InputMapping 从全局作用域解析出当前节点的输入作用域。
     */
    public VariableScope resolveInputs(List<InputMapping> inputs, VariableScope global) {
        VariableScope in = new VariableScope();
        if (inputs == null || inputs.isEmpty()) {
            // 默认：节点可直接读取全局作用域（透传），这里返回空作用域，执行器按需读取 global
            return in;
        }
        for (InputMapping m : inputs) {
            Object value = resolve(m.source(), global);
            in.set(m.target(), value);
        }
        return in;
    }

    /**
     * 解析点路径表达式。
     *
     * @param expr  表达式（点路径或引号字面量）
     * @param scope 解析上下文
     * @return 解析值，未命中返回 null
     */
    @SuppressWarnings("unchecked")
    public Object resolve(String expr, VariableScope scope) {
        if (expr == null) {
            return null;
        }
        String trimmed = expr.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        Object current = scope.asMap();
        for (String seg : trimmed.split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(seg);
            } else if (current instanceof List<?> list) {
                try {
                    int idx = Integer.parseInt(seg);
                    current = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    throw new WorkflowException("数组索引非法: " + seg + " in " + expr);
                }
            } else {
                // 路径中断（当前节点非容器）
                return null;
            }
        }
        return current;
    }

    /** 简单的点路径写入（用于把节点输出整体挂到全局命名空间）。 */
    public void setByPath(VariableScope scope, String path, Object value) {
        List<String> segs = new ArrayList<>(List.of(path.split("\\.")));
        if (segs.size() == 1) {
            scope.set(path, value);
            return;
        }
        Map<String, Object> cursor = scope.asMap();
        for (int i = 0; i < segs.size() - 1; i++) {
            Object next = cursor.get(segs.get(i));
            if (!(next instanceof Map)) {
                next = new java.util.LinkedHashMap<String, Object>();
                cursor.put(segs.get(i), next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(segs.get(segs.size() - 1), value);
    }
}
