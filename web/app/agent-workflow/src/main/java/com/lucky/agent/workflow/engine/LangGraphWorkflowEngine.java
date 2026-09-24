package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 基于 LangGraph4j 的工作流引擎：把 {@link WorkflowDef} 编译为 {@link StateGraph}，
 * 由框架负责节点调度与条件路由，节点执行复用既有 {@link NodeExecutor} 体系与适配器缝隙。
 *
 * <p>与 {@link WorkflowEngine}（自研调度）的差异：</p>
 * <ul>
 *   <li><b>调度下沉到框架</b>：不再自维护 pending/fired/队列；分支语义由
 *       {@code addConditionalEdges} 表达，条件求值复用 {@link ConditionEvaluator}。</li>
 *   <li><b>静态校验仍在本模块</b>：{@link DagCompiler} 保留，用作编译前的结构/环/孤立节点校验
 *       （LangGraph4j 不负责这类业务约束），并供 API 预览使用。</li>
 *   <li><b>事件契约不变</b>：节点进入/完成/跳过/失败仍经 {@link WorkflowEventBus} 发布，SSE 与前端零改动。</li>
 *   <li><b>失败即终止</b>：节点失败写入 state，其后继一律路由至 {@link GraphDefinition#END}（fail-fast）。</li>
 *   <li><b>START / END 节点真实建模</b>：START/END 注册为图中的真实节点，
 *       使 {@code instance.nodeOf(...)}、事件序列与旧引擎保持一致。</li>
 * </ul>
 *
 * <p>执行语义：单实例内确定性执行；条件边为 true 才触发，全部未命中则路由到 END（避免死锁）。
 * 与旧引擎的已知差异仅在「多入边汇聚」：LangGraph4j 为「任一入边触发即执行下游」的图语义，
 * 不做旧引擎的「全部入边解析完毕再判定」同步等待——该差异在无环 DAG + 单分支场景下不可观测，
 * 如需严格 join 语义，后续以显式 join 节点建模。</p>
 */
public class LangGraphWorkflowEngine extends AbstractWorkflowEngine {

    /** 条件路由兜底键：全部条件未命中时结束工作流。 */
    private static final String FALLBACK = "end-fallback";

    /** 无条件边的路由键（恒命中）。 */
    private static final String UNCONDITIONAL = "unconditional";

    public LangGraphWorkflowEngine(DagCompiler compiler,
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

    // ---------------- 执行核心 ----------------

    @Override
    protected void execute(WorkflowDef definition, WorkflowInstance instance) throws Exception {
        // 静态校验（结构/环/孤立节点）——LangGraph4j 不负责业务约束；校验失败直接抛出（与旧引擎一致）
        CompiledWorkflow cw = compiler.compile(definition);
        publish(instance, null, WorkflowEventType.WORKFLOW_STARTED, "工作流开始执行: " + definition.name());

        StateGraph<WorkflowState> graph = buildGraph(cw, instance);
        var compiled = graph.compile();
        // 安全阀：MVP 为无环 DAG；按「节点数 × 分支重入」放宽迭代上限，防御异常自环
        compiled.setMaxIterations(Math.max(32, cw.nodes().size() * 6));

        WorkflowState finalState = compiled.invoke(WorkflowState.initial(instance.getVariables().asMap()).data())
                .orElseThrow(() -> new IllegalStateException("工作流图无最终状态"));

        // 回填最终全局作用域（节点输出已随 state 传递）
        instance.getVariables().merge(new VariableScope(finalState.variables()));

        if (finalState.failed()) {
            stateMachine.transition(instance, WorkflowStatus.FAILED, finalState.error());
            persistProgress(instance);
            publish(instance, null, WorkflowEventType.WORKFLOW_FAILED, "工作流失败: " + finalState.error());
            return;
        }
        stateMachine.transition(instance, WorkflowStatus.COMPLETED);
        persistProgress(instance);
        publish(instance, null, WorkflowEventType.WORKFLOW_COMPLETED, "工作流执行完成: " + definition.name());
    }

    /** 把编译后的工作流组装为 LangGraph4j 状态图。 */
    private StateGraph<WorkflowState> buildGraph(CompiledWorkflow cw, WorkflowInstance instance) throws Exception {
        StateGraph<WorkflowState> graph = new StateGraph<>(WorkflowState::new);

        // 1) 注册全部节点（含 START/END），使实例记录与事件序列与旧引擎一致
        Map<String, String> graphIds = new HashMap<>();
        for (NodeDef node : cw.nodes().values()) {
            String graphId = graphNodeId(node);
            graphIds.put(node.id(), graphId);
            graph.addNode(graphId, actionFor(node, instance));
        }

        // 2) 入口：框架 START → 工作流 START 节点
        graph.addEdge(GraphDefinition.START, graphIds.get(cw.startNodeId()));

        // 3) 业务边 + 末端节点 → 框架 END
        for (NodeDef node : cw.nodes().values()) {
            String source = graphIds.get(node.id());
            List<EdgeDef> out = cw.outgoing().getOrDefault(node.id(), List.of());
            linkSuccessors(graph, source, out, graphIds);
        }
        return graph;
    }

    /**
     * 节点在图中的 id：START/END 加内部前缀，避免与业务节点 id 语义混淆。
     *
     * <p>注意：LangGraph4j 校验节点 id 合法性，**不允许以 {@code __} 开头**
     * （会抛 {@code GraphStateException: [id that start with %s] is not a valid node id!}），
     * 故此处使用 {@code entry-} / {@code exit-} 前缀，且业务节点 id 冲突时追加去重后缀。</p>
     */
    private String graphNodeId(NodeDef node) {
        return switch (node.type()) {
            case START -> "entry-" + node.id();
            case END -> "exit-" + node.id();
            default -> node.id();
        };
    }

    /**
     * 依据出边把 source 连到后继。
     *
     * <p>为保证 fail-fast（节点失败后其下游一律不得执行），**所有出边统一走条件路由**：
     * 路由函数先判失败态 → 一律收尾至 END；否则按边语义决定目标。</p>
     */
    private void linkSuccessors(StateGraph<WorkflowState> graph, String source,
                                List<EdgeDef> out, Map<String, String> graphIds) throws Exception {
        if (out.isEmpty()) {
            // 无出边：末端节点，连到框架 END
            graph.addEdge(source, GraphDefinition.END);
            return;
        }
        // 路由键 → 目标节点：无条件边用固定键，条件边用边 id
        Map<String, String> mappings = new HashMap<>();
        for (EdgeDef edge : out) {
            mappings.put(routeKeyOf(edge), graphIds.getOrDefault(edge.target(), GraphDefinition.END));
        }
        mappings.put(FALLBACK, GraphDefinition.END);
        graph.addConditionalEdges(source,
                AsyncEdgeAction.edge_async(state -> routeKey(state, out)),
                mappings);
    }

    /** 边对应的路由键：无条件边使用固定键（恒命中），条件边以边 id 为键。 */
    private static String routeKeyOf(EdgeDef edge) {
        return edge.isConditional() ? edge.id() : UNCONDITIONAL;
    }

    /**
     * 条件路由：失败态一律收尾至 END（fail-fast）；否则按出边顺序取首个命中边作为路由键；
     * 全部未命中返回兜底键 → END（工作流正常结束）。
     */
    private String routeKey(WorkflowState state, List<EdgeDef> out) {
        if (state.failed()) {
            return FALLBACK;
        }
        for (EdgeDef edge : out) {
            if (!edge.isConditional()) {
                // 无条件边：直接命中（键为 UNCONDITIONAL）
                return UNCONDITIONAL;
            }
            if (conditionEvaluator.evaluate(edge.condition(), new VariableScope(state.variables()))) {
                return edge.id();
            }
        }
        return FALLBACK;
    }

    /**
     * 单节点动作：执行节点并返回状态增量。
     *
     * <p>LangGraph4j 按不可变语义合并返回值，因此必须返回「新构造的可变 Map」，
     * 不可返回 {@code Map.of()} 或 state 内部 map（否则触发 {@code UnsupportedOperationException}）。</p>
     */
    private AsyncNodeAction<WorkflowState> actionFor(NodeDef node, WorkflowInstance instance) {
        return state -> CompletableFuture.completedFuture(executeNode(node, state, instance));
    }

    private Map<String, Object> executeNode(NodeDef node, WorkflowState state, WorkflowInstance instance) {
        // 以可变副本承载本次全局作用域（不直接改动 state 内部 map）
        Map<String, Object> vars = new LinkedHashMap<>(state.variables());
        VariableScope global = new VariableScope(vars);

        NodeResult result = runNode(node, global, instance);

        Map<String, Object> delta = new HashMap<>();
        if (result.isSuccess()) {
            delta.put(WorkflowState.KEY_VARS, global.asMap());
            return delta;
        }
        if (result.status() == NodeStatus.SKIPPED) {
            return delta;
        }
        // 失败：写入失败态；条件路由将收尾至 END
        delta.put(WorkflowState.KEY_VARS, global.asMap());
        delta.put(WorkflowState.KEY_STATUS, "FAILED");
        delta.put(WorkflowState.KEY_ERROR, result.error());
        return delta;
    }
}
