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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 工作流引擎公共骨架：承载与具体调度实现无关的公共部件（依赖装配、实例生命周期、
 * 节点执行、输出回写、事件发布）。
 *
 * <p>提供两种调度实现：</p>
 * <ul>
 *   <li>{@link WorkflowEngine}：自研 Kahn 拓扑 + 手写队列调度（legacy，默认）。</li>
 *   <li>{@link LangGraphWorkflowEngine}：LangGraph4j {@code StateGraph} 驱动调度（可配置切换）。</li>
 * </ul>
 * 两者共用同一套执行器、适配器、事件契约与状态机，故切换对 REST/SSE 与前端完全透明。
 */
public abstract class AbstractWorkflowEngine implements SubflowInvoker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final DagCompiler compiler;
    protected final WorkflowStateMachine stateMachine;
    protected final MappingEvaluator mappingEvaluator;
    protected final ConditionEvaluator conditionEvaluator;
    protected final WorkflowEventBus eventBus;
    protected final List<NodeExecutor> executors;
    protected final LlmAdapter llmAdapter;
    protected final ToolAdapter toolAdapter;
    protected final SandboxAdapter sandboxAdapter;
    protected final WorkflowRepository workflowRepository;
    protected final WorkflowInstanceRepository instanceRepository;
    protected final ExecutorService asyncExecutor;

    protected AbstractWorkflowEngine(DagCompiler compiler,
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

    // ---------------- 对外 API（契约一致，引擎可互换） ----------------

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

    // ---------------- 执行骨架 ----------------

    protected void safeExecute(WorkflowDef definition, WorkflowInstance instance) {
        try {
            execute(definition, instance);
        } catch (WorkflowException e) {
            // 编译期静态校验失败（结构/环/端点）：属于「定义非法」，必须向调用方抛出
            markFailed(instance, describe(e));
            throw e;
        } catch (Exception e) {
            log.error("工作流执行异常: {}", definition.id(), e);
            markFailed(instance, describe(e));
        } finally {
            instanceRepository.save(instance);
        }
    }

    private void markFailed(WorkflowInstance instance, String error) {
        logLine(instance, null, WorkflowEventType.WORKFLOW_FAILED, "工作流执行异常: " + error);
        WorkflowStatus current = instance.getStatus();
        if (current == WorkflowStatus.RUNNING || current == WorkflowStatus.SUSPENDED) {
            stateMachine.transition(instance, WorkflowStatus.FAILED, error);
        }
    }

    /**
     * 把异常转成「一定非空且可读」的失败原因。
     *
     * <p><b>为何不能直接用 {@code e.getMessage()}：</b>NPE、部分 {@code IndexOutOfBoundsException}
     * 的 message 本身就是 null，直接透传会让实例的 {@code error} 字段为 null ——
     * 执行面板只能显示「失败」而给不出任何原因，用户无法自查。此处兜底为异常类型名。</p>
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return e.getClass().getSimpleName() + ": " + message;
        }
        return e.getClass().getSimpleName() + "（无详细信息，请查看后端日志）";
    }

    /**
     * 把当前实例快照刷到仓储 —— 节点状态变更后、发布对应事件之前调用。
     *
     * <p><b>为何必须按节点落盘（而非只在起止落一次）：</b>SSE 事件只携带类型与文案，
     * 不含变量快照；订阅方收到事件后必须回查 {@code GET /instances/{id}} 才能渲染
     * 节点耗时、入参与产出。若引擎只在开头（此时 {@code nodeInstances} 尚为空）
     * 与 {@code finally} 各落一次，则整个运行期间的回查都只能看到空列表，
     * 执行面板要等流程全部结束才一次性出现 —— 这正是「执行界面看着像没在跑」的根因。</p>
     *
     * <p><b>成本与边界：</b>按「节点状态变更」而非「每轮迭代」落盘，写入量与节点数同阶，
     * 与 LLM token 数无关。落盘失败只告警不抛出：进度记录属旁路，不应让一次磁盘抖动
     * 打断正在执行的流程（终态快照由 {@link #safeExecute} 的 finally 兜底重试）。</p>
     */
    protected void persistProgress(WorkflowInstance instance) {
        if (instanceRepository == null) {
            return;
        }
        try {
            instanceRepository.save(instance);
        } catch (Exception e) {
            log.warn("工作流进度落盘失败（不影响执行）: {} - {}", instance.getInstanceId(), e.getMessage());
        }
    }

    /** 由子类实现的具体调度。 */
    protected abstract void execute(WorkflowDef definition, WorkflowInstance instance) throws Exception;

    // ---------------- 节点执行（两种调度共用） ----------------

    /** 执行单个节点：解析输入 → 执行器 → 回写输出 → 发事件；失败置 FAILED 由调用方 fail-fast。 */
    protected NodeResult runNode(NodeDef node, VariableScope global, WorkflowInstance instance) {
        NodeInstance ni = instance.node(node.id(), node.name(), node.type());
        ni.markRunning();
        instance.setCurrentNodeId(node.id());

        // 入参解析必须先于落盘：LLM 节点可能跑几十秒，若首次快照里没有入参，
        // 执行面板在整个「运行中」窗口的「入参」列都只能是空白 —— 而用户最想看的正是
        // 「到底喂给模型的是什么」。故解析 → 落盘 → 发事件，一次写入同时覆盖「运行中 + 入参」。
        VariableScope input;
        try {
            input = mappingEvaluator.resolveInputs(node.inputs(), global);
        } catch (Exception e) {
            // 映射解析失败属节点级问题：只判该节点失败，不炸整条工作流 ——
            // 否则用户只看到工作流 FAILED，却不知道哪个节点、哪条映射出的问题
            String reason = "输入映射解析失败: " + describe(e);
            ni.markFailed(reason);
            persistProgress(instance);
            publish(instance, node.id(), WorkflowEventType.NODE_FAILED,
                    "节点失败: " + node.name() + " - " + reason);
            return NodeResult.failure(reason);
        }
        ni.setInput(input);
        persistProgress(instance);
        publish(instance, node.id(), WorkflowEventType.NODE_STARTED, "节点开始: " + node.name());

        NodeResult result = executeNode(node, input, global, instance);
        if (result == null) {
            result = NodeResult.failure("执行器返回空结果: " + node.id());
        }

        if (result.isSuccess()) {
            ni.markCompleted(result.output());
            applyOutputs(node, result.output(), global);
            persistProgress(instance);
            publish(instance, node.id(), WorkflowEventType.NODE_COMPLETED, "节点完成: " + node.name());
        } else if (result.status() == NodeStatus.SKIPPED) {
            ni.markSkipped();
            persistProgress(instance);
            publish(instance, node.id(), WorkflowEventType.NODE_SKIPPED, "节点跳过: " + node.name());
        } else {
            ni.markFailed(result.error());
            persistProgress(instance);
            publish(instance, node.id(), WorkflowEventType.NODE_FAILED,
                    "节点失败: " + node.name() + " - " + result.error());
        }
        return result;
    }

    /** 标记节点为跳过（前置条件未满足）。 */
    protected void skipNode(NodeDef node, WorkflowInstance instance, String reason) {
        NodeInstance ni = instance.node(node.id(), node.name(), node.type());
        ni.markSkipped();
        persistProgress(instance);
        publish(instance, node.id(), WorkflowEventType.NODE_SKIPPED, "节点跳过（" + reason + "）: " + node.name());
    }

    protected NodeResult executeNode(NodeDef node, VariableScope input, VariableScope global, WorkflowInstance instance) {
        NodeExecutor executor = executors.stream()
                .filter(e -> e.supports(node.type()))
                .findFirst()
                .orElse(null);
        if (executor == null) {
            return NodeResult.failure("未找到节点类型执行器: " + node.type());
        }
        NodeExecutionContext ctx = new NodeExecutionContext(
                node, global, input, instance, eventBus,
                llmAdapter, toolAdapter, sandboxAdapter, mappingEvaluator, conditionEvaluator, this);
        try {
            return executor.execute(ctx);
        } catch (Exception e) {
            return NodeResult.failure("执行器异常: " + e.getMessage());
        }
    }

    /** 回写节点输出到全局作用域：有显式 outputs 用连接器映射，否则以节点 id 命名空间整体暴露。 */
    protected void applyOutputs(NodeDef node, VariableScope output, VariableScope global) {
        if (node.outputs() != null && !node.outputs().isEmpty()) {
            mappingEvaluator.applyOutputs(node.type(), node.outputs(), output, global);
        } else {
            global.set(node.id(), new LinkedHashMap<>(output.asMap()));
        }
    }

    /** 出边触发判定：无条件边恒触发，条件边按表达式求值。 */
    protected boolean triggered(EdgeDef edge, VariableScope global) {
        return !edge.isConditional() || conditionEvaluator.evaluate(edge.condition(), global);
    }

    // ---------------- 事件 ----------------

    protected void publish(WorkflowInstance instance, String nodeId, WorkflowEventType type, String message) {
        eventBus.publish(WorkflowEvent.of(instance.getInstanceId(), instance.getWorkflowId(), nodeId, type, message));
    }

    /** 事件别名（与 {@link #publish} 等价），用于区分「整条工作流级」事件的可读性。 */
    protected void logLine(WorkflowInstance instance, String nodeId, WorkflowEventType type, String message) {
        publish(instance, nodeId, type, message);
    }
}
