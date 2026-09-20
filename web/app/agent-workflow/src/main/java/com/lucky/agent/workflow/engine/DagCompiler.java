package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 编译器：把工作流定义编译为可执行计划。
 * <p>编译期静态校验（对应设计中的「编译：DAG → 可执行计划」）：</p>
 * <ol>
 *   <li>结构校验：节点 id 唯一、存在唯一 START、至少一个 END、边端点存在（委托 {@link WorkflowDef#validateOrThrow()}）。</li>
 *   <li>环检测：Kahn 拓扑排序，若存在环则拒绝编译（防止执行期死循环）。</li>
 *   <li>连通性：构建出入边邻接表与拓扑序。</li>
 * </ol>
 */
public class DagCompiler {

    public CompiledWorkflow compile(WorkflowDef definition) {
        definition.validateOrThrow();

        Map<String, NodeDef> nodes = new LinkedHashMap<>();
        for (NodeDef n : definition.nodes()) {
            nodes.put(n.id(), n);
        }

        Map<String, List<EdgeDef>> outgoing = new HashMap<>();
        Map<String, List<EdgeDef>> incoming = new HashMap<>();
        for (String id : nodes.keySet()) {
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
        }
        for (EdgeDef e : definition.edges()) {
            outgoing.get(e.source()).add(e);
            incoming.get(e.target()).add(e);
        }

        List<String> topo = kahnTopologicalSort(nodes, outgoing, incoming);

        String startId = nodes.values().stream()
                .filter(n -> n.type() == WorkflowNodeType.START)
                .map(NodeDef::id)
                .findFirst()
                .orElseThrow(() -> new WorkflowException("缺少 START 节点"));

        return new CompiledWorkflow(definition, nodes, outgoing, incoming, topo, startId);
    }

    /** Kahn 算法拓扑排序；存在环时抛出异常。 */
    private List<String> kahnTopologicalSort(Map<String, NodeDef> nodes,
                                             Map<String, List<EdgeDef>> outgoing,
                                             Map<String, List<EdgeDef>> incoming) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodes.keySet()) {
            inDegree.put(id, incoming.get(id).size());
        }
        Deque<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> {
            if (deg == 0) {
                queue.add(id);
            }
        });

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (EdgeDef e : outgoing.get(id)) {
                int deg = inDegree.merge(e.target(), -1, Integer::sum);
                if (deg == 0) {
                    queue.add(e.target());
                }
            }
        }

        if (order.size() != nodes.size()) {
            List<String> cyclic = new ArrayList<>(nodes.keySet());
            cyclic.removeAll(order);
            throw new WorkflowException("工作流存在环，无法编译为 DAG（涉及节点: " + cyclic + "）");
        }
        return order;
    }
}
