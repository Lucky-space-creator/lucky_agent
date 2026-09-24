package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 工作流引擎（legacy 调度器）：编译 DAG、按拓扑/条件路由逐节点执行、驱动状态流转、聚合结果。
 *
 * <p>执行语义：</p>
 * <ul>
 *   <li>以 START 为起点，节点的就绪条件 = 其所有入边均已「解析」（触发或未触发）。</li>
 *   <li>边的 condition 求值为 true 则触发（计入 fired）；否则丢弃。</li>
 *   <li>节点入边全部解析且至少一条触发 → 执行；全部未触发 → 跳过并级联下传。</li>
 *   <li>节点执行前经连接器解析输入、执行后回写输出到全局作用域；默认以节点 id 命名空间暴露输出。</li>
 *   <li>任一节点失败 → 工作流置 FAILED（fail-fast）；全部完成 → COMPLETED。</li>
 * </ul>
 * <p>该实现为默认引擎（{@code lucky.workflow.execution.engine=legacy}）；
 * {@link LangGraphWorkflowEngine} 为等价的框架化实现，两者可配置切换。</p>
 */
public class WorkflowEngine extends AbstractWorkflowEngine {

    public WorkflowEngine(DagCompiler compiler,
                          WorkflowStateMachine stateMachine,
                          MappingEvaluator mappingEvaluator,
                          ConditionEvaluator conditionEvaluator,
                          WorkflowEventBus eventBus,
                          List<NodeExecutor> executors,
                          LlmAdapter llmAdapter,
                          ToolAdapter toolAdapter,
                          SandboxAdapter sandboxAdapter,
                          WorkflowRepository workflowRepository,
                          WorkflowInstanceRepository instanceRepository,
                          ExecutorService asyncExecutor) {
        super(compiler, stateMachine, mappingEvaluator, conditionEvaluator, eventBus, executors,
                llmAdapter, toolAdapter, sandboxAdapter, workflowRepository, instanceRepository, asyncExecutor);
    }

    @Override
    protected void execute(WorkflowDef definition, WorkflowInstance instance) {
        CompiledWorkflow cw = compiler.compile(definition);
        publish(instance, null, WorkflowEventType.WORKFLOW_STARTED, "工作流开始执行: " + definition.name());

        Map<String, Integer> pending = new HashMap<>();
        Map<String, Integer> fired = new HashMap<>();
        for (String id : cw.nodes().keySet()) {
            pending.put(id, cw.incoming().get(id).size());
            fired.put(id, 0);
        }

        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        fired.put(cw.startNodeId(), 1);
        queue.add(cw.startNodeId());

        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            if (visited.contains(nodeId)) {
                continue;
            }
            visited.add(nodeId);

            NodeDef node = cw.nodes().get(nodeId);
            NodeResult result = runNode(node, instance.getVariables(), instance);

            if (!result.isSuccess() && result.status() != com.lucky.agent.workflow.domain.enums.NodeStatus.SKIPPED) {
                stateMachine.transition(instance, WorkflowStatus.FAILED, result.error());
                persistProgress(instance);
                publish(instance, nodeId, WorkflowEventType.WORKFLOW_FAILED, "工作流失败");
                return;
            }

            routeSuccessors(cw, nodeId, instance, pending, fired, queue, visited);
        }

        stateMachine.transition(instance, WorkflowStatus.COMPLETED);
        persistProgress(instance);
        publish(instance, null, WorkflowEventType.WORKFLOW_COMPLETED, "工作流执行完成: " + definition.name());
    }

    /** 节点完成后解析其出边，决定下游就绪/跳过。 */
    private void routeSuccessors(CompiledWorkflow cw, String nodeId, WorkflowInstance instance,
                                 Map<String, Integer> pending, Map<String, Integer> fired,
                                 Deque<String> queue, Set<String> visited) {
        for (EdgeDef edge : cw.outgoing().getOrDefault(nodeId, List.of())) {
            if (triggered(edge, instance.getVariables())) {
                fired.merge(edge.target(), 1, Integer::sum);
            }
            int remaining = pending.merge(edge.target(), -1, Integer::sum);
            if (remaining <= 0 && !visited.contains(edge.target())) {
                if (fired.getOrDefault(edge.target(), 0) > 0) {
                    queue.add(edge.target());
                } else {
                    skipCascade(cw, edge.target(), instance, pending, fired, queue, visited);
                }
            }
        }
    }

    /** 级联跳过：节点无任何触发入边时标记 SKIPPED，并继续向下游传播「未触发」。 */
    private void skipCascade(CompiledWorkflow cw, String nodeId, WorkflowInstance instance,
                             Map<String, Integer> pending, Map<String, Integer> fired,
                             Deque<String> queue, Set<String> visited) {
        if (visited.contains(nodeId)) {
            return;
        }
        visited.add(nodeId);
        NodeDef node = cw.nodes().get(nodeId);
        skipNode(node, instance, "前置条件未满足");

        for (EdgeDef edge : cw.outgoing().getOrDefault(nodeId, List.of())) {
            int remaining = pending.merge(edge.target(), -1, Integer::sum);
            if (remaining <= 0 && !visited.contains(edge.target())) {
                if (fired.getOrDefault(edge.target(), 0) > 0) {
                    queue.add(edge.target());
                } else {
                    skipCascade(cw, edge.target(), instance, pending, fired, queue, visited);
                }
            }
        }
    }

    /** 显式保留 END 节点执行入口（供未来扩展），当前 END 由拓扑队列自然执行。 */
    @SuppressWarnings("unused")
    private boolean isEnd(NodeDef node) {
        return node.type() == WorkflowNodeType.END;
    }
}
