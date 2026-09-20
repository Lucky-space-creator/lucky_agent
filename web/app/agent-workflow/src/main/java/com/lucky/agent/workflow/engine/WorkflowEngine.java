package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.NodeInstance;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.event.WorkflowEvent;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.exception.WorkflowException;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 工作流引擎（调度器）：编译 DAG、按拓扑/条件路由逐节点执行、驱动状态流转、聚合结果。
 *
 * <p>执行语义：</p>
 * <ul>
 *   <li>以 START 为起点，节点的就绪条件 = 其所有入边均已「解析」（触发或未触发）。</li>
 *   <li>边的 condition 求值为 true 则触发（计入 fired）；否则丢弃。</li>
 *   <li>节点入边全部解析且至少一条触发 → 执行；全部未触发 → 跳过并级联下传。</li>
 *   <li>节点执行前经连接器解析输入、执行后回写输出到全局作用域；默认以节点 id 命名空间暴露输出。</li>
 *   <li>任一节点失败 → 工作流置 FAILED（fail-fast）；全部完成 → COMPLETED。</li>
 * </ul>
 * 说明：MVP 为单实例内确定性顺序调度（正确性优先）；并行分支调度为后续扩展点。
 */
public class WorkflowEngine implements SubflowInvoker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final DagCompiler compiler;
    private final WorkflowStateMachine stateMachine;
    private final MappingEvaluator mappingEvaluator;
    private final ConditionEvaluator conditionEvaluator;
    private final WorkflowEventBus eventBus;
    private final List<NodeExecutor> executors;
    private final LlmAdapter llmAdapter;
    private final ToolAdapter toolAdapter;
    private final SandboxAdapter sandboxAdapter;
    private final WorkflowRepository workflowRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final ExecutorService asyncExecutor;

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
        this.compiler = compiler;
        this.stateMachine = stateMachine;
        this.mappingEvaluator = mappingEvaluator;
        this.conditionEvaluator = conditionEvaluator;
        this.eventBus = eventBus;
        this.executors = executors == null ? List.of() : List.copyOf(executors);
        this.llmAdapter = llmAdapter;
        this.toolAdapter = toolAdapter;
        this.sandboxAdapter = sandboxAdapter;
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.asyncExecutor = asyncExecutor;
    }

    // ---------------- 对外 API ----------------

    public WorkflowInstance run(WorkflowDef definition, Map<String, Object> initialVars) {
        return run(definition, new VariableScope(initialVars), RunMode.SYNC);
    }

    public WorkflowInstance run(WorkflowDef definition, VariableScope initialVars, RunMode mode) {
        WorkflowInstance instance = new WorkflowInstance(definition.id(), definition.name());
        if (initialVars != null) {
            instance.getVariables().merge(initialVars);
        }
        instanceRepository.save(instance);

        if (mode == RunMode.ASYNC && asyncExecutor != null) {
            asyncExecutor.submit(() -> safeExecute(definition, instance));
        } else {
            safeExecute(definition, instance);
        }
        return instance;
    }

    /** 编译校验（供 API 预览/编辑时静态检查使用）。 */
    public CompiledWorkflow compile(WorkflowDef definition) {
        return compiler.compile(definition);
    }

    @Override
    public WorkflowInstance invoke(String subWorkflowId, VariableScope inputs) {
        WorkflowDef sub = workflowRepository.findById(subWorkflowId)
                .orElseThrow(() -> new WorkflowException("子工作流不存在: " + subWorkflowId));
        return run(sub, inputs, RunMode.SYNC);
    }

    // ---------------- 执行核心 ----------------

    private void safeExecute(WorkflowDef definition, WorkflowInstance instance) {
        try {
            execute(definition, instance);
        } catch (Exception e) {
            log.error("工作流执行异常: {}", definition.id(), e);
            instance.markFailed(e.getMessage());
            publish(instance, null, WorkflowEventType.WORKFLOW_FAILED, "工作流执行异常: " + e.getMessage());
        } finally {
            instanceRepository.save(instance);
        }
    }

    private void execute(WorkflowDef definition, WorkflowInstance instance) {
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
            NodeInstance ni = instance.node(nodeId, node.name(), node.type());
            ni.markRunning();
            instance.setCurrentNodeId(nodeId);
            publish(instance, nodeId, WorkflowEventType.NODE_STARTED, "节点开始: " + node.name());

            VariableScope input = mappingEvaluator.resolveInputs(node.inputs(), instance.getVariables());
            ni.setInput(input);

            NodeResult result = executeNode(node, input, instance);

            if (result.isSuccess()) {
                ni.markCompleted(result.output());
                applyOutputs(node, result.output(), instance);
                publish(instance, nodeId, WorkflowEventType.NODE_COMPLETED, "节点完成: " + node.name());
            } else if (result.status() == NodeStatus.SKIPPED) {
                ni.markSkipped();
                publish(instance, nodeId, WorkflowEventType.NODE_SKIPPED, "节点跳过: " + node.name());
            } else {
                ni.markFailed(result.error());
                publish(instance, nodeId, WorkflowEventType.NODE_FAILED, "节点失败: " + node.name() + " - " + result.error());
                stateMachine.transition(instance, WorkflowStatus.FAILED, result.error());
                publish(instance, nodeId, WorkflowEventType.WORKFLOW_FAILED, "工作流失败");
                return;
            }

            routeSuccessors(cw, nodeId, instance, pending, fired, queue, visited);
        }

        stateMachine.transition(instance, WorkflowStatus.COMPLETED);
        publish(instance, null, WorkflowEventType.WORKFLOW_COMPLETED, "工作流执行完成: " + definition.name());
    }

    /** 节点完成后解析其出边，决定下游就绪/跳过。 */
    private void routeSuccessors(CompiledWorkflow cw, String nodeId, WorkflowInstance instance,
                                 Map<String, Integer> pending, Map<String, Integer> fired,
                                 Deque<String> queue, Set<String> visited) {
        for (EdgeDef edge : cw.outgoing().getOrDefault(nodeId, List.of())) {
            boolean trigger = !edge.isConditional()
                    || conditionEvaluator.evaluate(edge.condition(), instance.getVariables());
            if (trigger) {
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
        NodeInstance ni = instance.node(nodeId, node.name(), node.type());
        ni.markSkipped();
        publish(instance, nodeId, WorkflowEventType.NODE_SKIPPED, "节点跳过（前置条件未满足）: " + node.name());

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

    private NodeResult executeNode(NodeDef node, VariableScope input, WorkflowInstance instance) {
        NodeExecutor executor = executors.stream()
                .filter(e -> e.supports(node.type()))
                .findFirst()
                .orElse(null);
        if (executor == null) {
            return NodeResult.failure("未找到节点类型执行器: " + node.type());
        }
        NodeExecutionContext ctx = new NodeExecutionContext(
                node, instance.getVariables(), input, instance, eventBus,
                llmAdapter, toolAdapter, sandboxAdapter, mappingEvaluator, conditionEvaluator, this);
        try {
            NodeResult result = executor.execute(ctx);
            return result == null ? NodeResult.failure("执行器返回空结果: " + node.id()) : result;
        } catch (Exception e) {
            return NodeResult.failure("执行器异常: " + e.getMessage());
        }
    }

    /** 回写节点输出：有显式 outputs 用连接器映射，否则以节点 id 命名空间整体暴露。 */
    private void applyOutputs(NodeDef node, VariableScope output, WorkflowInstance instance) {
        if (node.outputs() != null && !node.outputs().isEmpty()) {
            mappingEvaluator.applyOutputs(node.type(), node.outputs(), output, instance.getVariables());
        } else {
            instance.getVariables().set(node.id(), new LinkedHashMap<>(output.asMap()));
        }
    }

    private void publish(WorkflowInstance instance, String nodeId, WorkflowEventType type, String message) {
        eventBus.publish(WorkflowEvent.of(instance.getInstanceId(), instance.getWorkflowId(), nodeId, type, message));
    }
}
