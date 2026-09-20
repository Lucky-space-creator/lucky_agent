package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.TriggerType;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义（静态蓝图）：节点集合 + 边集合 + 触发器 + 元信息。
 * <p>定义与实例分离：{@code WorkflowDef} 描述「长什么样」，
 * {@code WorkflowInstance} 描述「某一次运行」。</p>
 */
public record WorkflowDef(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("version") int version,
        @JsonProperty("nodes") List<NodeDef> nodes,
        @JsonProperty("edges") List<EdgeDef> edges,
        @JsonProperty("trigger") TriggerDef trigger,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("createdAt") Long createdAt,
        @JsonProperty("updatedAt") Long updatedAt) {

    public WorkflowDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WorkflowDef.id 不能为空");
        }
        nodes = (nodes == null) ? List.of() : List.copyOf(nodes);
        edges = (edges == null) ? List.of() : List.copyOf(edges);
        trigger = (trigger == null) ? TriggerDef.manual() : trigger;
    }

    /**
     * 基础结构校验（拓扑/环检测交由 {@code DagCompiler} 完成）。
     */
    public void validateOrThrow() {
        if (nodes.isEmpty()) {
            throw new WorkflowException("工作流必须至少包含一个节点: " + id);
        }
        long startCount = nodes.stream().filter(n -> n.type() == WorkflowNodeType.START).count();
        if (startCount != 1) {
            throw new WorkflowException("工作流必须且只能有 1 个 START 节点，当前: " + startCount);
        }
        long endCount = nodes.stream().filter(n -> n.type() == WorkflowNodeType.END).count();
        if (endCount < 1) {
            throw new WorkflowException("工作流至少需要有 1 个 END 节点");
        }
        List<String> ids = new ArrayList<>();
        for (NodeDef n : nodes) {
            if (ids.contains(n.id())) {
                throw new WorkflowException("节点 id 重复: " + n.id());
            }
            ids.add(n.id());
        }
        for (EdgeDef e : edges) {
            if (!ids.contains(e.source()) || !ids.contains(e.target())) {
                throw new WorkflowException("边的端点不存在: " + e.source() + " -> " + e.target());
            }
        }
    }

    public boolean isAutoTrigger() {
        return trigger != null && trigger.enabled()
                && trigger.type() != TriggerType.MANUAL;
    }

    /** 创建时打时间戳（首次落库）。 */
    public WorkflowDef withCreatedNow() {
        long now = System.currentTimeMillis();
        return new WorkflowDef(id, name, description, version, nodes, edges, trigger, enabled, now, now);
    }

    /** 更新时刷新 updatedAt。 */
    public WorkflowDef withUpdatedNow() {
        return new WorkflowDef(id, name, description, version, nodes, edges, trigger, enabled,
                createdAt, System.currentTimeMillis());
    }

    /** 切换启用状态。 */
    public WorkflowDef withEnabled(boolean value) {
        return new WorkflowDef(id, name, description, version, nodes, edges, trigger, value,
                createdAt, System.currentTimeMillis());
    }
}
