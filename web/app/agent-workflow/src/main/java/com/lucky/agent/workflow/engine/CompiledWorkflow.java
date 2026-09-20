package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.WorkflowDef;

import java.util.List;
import java.util.Map;

/**
 * 编译后的可执行工作流：由 {@link DagCompiler} 从 {@link WorkflowDef} 产出。
 * <p>包含节点索引、出入边邻接表、拓扑序与起始节点，供引擎 O(1) 访问。</p>
 *
 * @param definition       原始定义
 * @param nodes            节点索引（id → NodeDef）
 * @param outgoing         出边邻接表（nodeId → 边列表）
 * @param incoming         入边邻接表（nodeId → 边列表）
 * @param topologicalOrder 拓扑排序结果
 * @param startNodeId      START 节点 id
 */
public record CompiledWorkflow(
        WorkflowDef definition,
        Map<String, NodeDef> nodes,
        Map<String, List<EdgeDef>> outgoing,
        Map<String, List<EdgeDef>> incoming,
        List<String> topologicalOrder,
        String startNodeId) {
}
