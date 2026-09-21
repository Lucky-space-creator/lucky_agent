package com.lucky.agent.workflow;

import com.lucky.agent.workflow.adapter.LocalSandboxAdapter;
import com.lucky.agent.workflow.adapter.NoopLlmAdapter;
import com.lucky.agent.workflow.adapter.NoopToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.TriggerDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.engine.ConditionEvaluator;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.engine.LangGraphWorkflowEngine;
import com.lucky.agent.workflow.engine.MappingEvaluator;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.WorkflowStateMachine;
import com.lucky.agent.workflow.engine.executors.CodeNodeExecutor;
import com.lucky.agent.workflow.engine.executors.ConditionNodeExecutor;
import com.lucky.agent.workflow.engine.executors.EndNodeExecutor;
import com.lucky.agent.workflow.engine.executors.LlmNodeExecutor;
import com.lucky.agent.workflow.engine.executors.StartNodeExecutor;
import com.lucky.agent.workflow.engine.executors.SubflowNodeExecutor;
import com.lucky.agent.workflow.engine.executors.ToolNodeExecutor;
import com.lucky.agent.workflow.event.WorkflowEvent;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.repository.InMemoryWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LangGraph4j 工作流引擎测试：验证与旧 {@code WorkflowEngine} 等价的执行语义
 * （顺序执行、条件分支、失败即终止、事件流契约）。
 */
class LangGraphWorkflowEngineTest {

    private final MappingEvaluator mappingEvaluator = new MappingEvaluator();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator(mappingEvaluator);
    private final WorkflowEventBus eventBus = new WorkflowEventBus();

    private LangGraphWorkflowEngine newEngine() {
        List<NodeExecutor> executors = List.of(
                new StartNodeExecutor(),
                new EndNodeExecutor(),
                new LlmNodeExecutor(mappingEvaluator),
                new ToolNodeExecutor(),
                new ConditionNodeExecutor(),
                new CodeNodeExecutor(mappingEvaluator),
                new SubflowNodeExecutor());
        return new LangGraphWorkflowEngine(
                new DagCompiler(),
                new WorkflowStateMachine(),
                mappingEvaluator,
                conditionEvaluator,
                eventBus,
                executors,
                new NoopLlmAdapter(),
                new NoopToolAdapter(),
                new LocalSandboxAdapter(false, 5000, null),
                new InMemoryWorkflowRepository(),
                new InMemoryWorkflowInstanceRepository(),
                null);
    }

    /** START → LLM → CONDITION →(score>60) 通过 / 否则 拒绝。 */
    private WorkflowDef branchWorkflow() {
        NodeDef start = new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of());
        NodeDef classify = new NodeDef("classify", "分类", WorkflowNodeType.LLM,
                Map.of("prompt", "请对话题进行分类: ${topic}"), List.of(), List.of());
        NodeDef check = new NodeDef("check", "评分判断", WorkflowNodeType.CONDITION,
                Map.of("condition", "score > 60"), List.of(), List.of());
        NodeDef approve = new NodeDef("approve", "通过", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        NodeDef reject = new NodeDef("reject", "拒绝", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        List<EdgeDef> edges = List.of(
                new EdgeDef("e1", "start", "classify", null),
                new EdgeDef("e2", "classify", "check", null),
                new EdgeDef("e3", "check", "approve", "score > 60"),
                new EdgeDef("e4", "check", "reject", "score <= 60"));
        return new WorkflowDef("wf-branch", "分支工作流", "测试条件分支",
                1, List.of(start, classify, check, approve, reject), edges,
                TriggerDef.manual(), true, null, null);
    }

    @Test
    @DisplayName("高分分支：走 approve，reject 不被执行")
    void shouldRunHighScoreBranch() {
        LangGraphWorkflowEngine engine = newEngine();
        List<WorkflowEvent> events = new ArrayList<>();
        eventBus.subscribe(events::add);

        WorkflowInstance instance = engine.run(branchWorkflow(),
                new VariableScope(Map.of("topic", "退款", "score", 80)), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        // START / END 均为真实节点，全链路都有实例记录
        assertThat(instance.nodeOf("start").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("classify").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("check").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("approve").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        // 未命中的分支不被触达（图语义）
        assertThat(instance.nodeOf("reject")).isNull();
        assertThat(String.valueOf(instance.nodeOf("classify").getOutput().get("text")))
                .contains("noop-llm").contains("退款");
        assertThat(events).anyMatch(e -> e.type() == WorkflowEventType.WORKFLOW_COMPLETED);
    }

    @Test
    @DisplayName("低分分支：走 reject，approve 不被执行")
    void shouldRunLowScoreBranch() {
        LangGraphWorkflowEngine engine = newEngine();
        WorkflowInstance instance = engine.run(branchWorkflow(),
                new VariableScope(Map.of("topic", "咨询", "score", 30)), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.nodeOf("check").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("reject").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("approve")).isNull();
    }

    @Test
    @DisplayName("节点失败即终止：工作流 FAILED，下游 END 不执行")
    void shouldFailFastWhenNodeFails() {
        NodeDef start = new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of());
        NodeDef code = new NodeDef("code", "执行命令", WorkflowNodeType.CODE,
                Map.of("command", "echo hi"), List.of(), List.of());
        NodeDef end = new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        WorkflowDef def = new WorkflowDef("wf-code", "代码工作流", null, 1,
                List.of(start, code, end),
                List.of(new EdgeDef("e1", "start", "code", null), new EdgeDef("e2", "code", "end", null)),
                TriggerDef.manual(), true, null, null);

        WorkflowInstance instance = newEngine().run(def, new VariableScope(), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(instance.nodeOf("code").getStatus()).isEqualTo(NodeStatus.FAILED);
        // fail-fast：失败后下游 END 不被执行，故无实例记录
        assertThat(instance.nodeOf("end")).isNull();
    }

    @Test
    @DisplayName("线性链 START → LLM → END 顺利执行完成")
    void shouldRunLinearChain() {
        NodeDef start = new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of());
        NodeDef llm = new NodeDef("llm", "生成", WorkflowNodeType.LLM,
                Map.of("prompt", "总结: ${topic}"), List.of(), List.of());
        NodeDef end = new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        WorkflowDef def = new WorkflowDef("wf-linear", "线性工作流", null, 1,
                List.of(start, llm, end),
                List.of(new EdgeDef("e1", "start", "llm", null), new EdgeDef("e2", "llm", "end", null)),
                TriggerDef.manual(), true, null, null);

        WorkflowInstance instance = newEngine().run(def,
                new VariableScope(Map.of("topic", "测试")), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.nodeOf("llm").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("end").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        // 节点输出以节点 id 命名空间暴露到全局作用域
        assertThat(instance.getVariables().get("llm")).isNotNull();
    }

    @Test
    @DisplayName("编译校验复用 DagCompiler：环检测报错")
    void shouldRejectCyclicDefinition() {
        NodeDef start = new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of());
        NodeDef a = new NodeDef("a", "A", WorkflowNodeType.LLM, Map.of("prompt", "a"), List.of(), List.of());
        NodeDef b = new NodeDef("b", "B", WorkflowNodeType.LLM, Map.of("prompt", "b"), List.of(), List.of());
        NodeDef end = new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        WorkflowDef def = new WorkflowDef("wf-cycle", "环工作流", null, 1,
                List.of(start, a, b, end),
                List.of(new EdgeDef("e1", "start", "a", null),
                        new EdgeDef("e2", "a", "b", null),
                        new EdgeDef("e3", "b", "a", null),
                        new EdgeDef("e4", "b", "end", null)),
                TriggerDef.manual(), true, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> newEngine().run(def, new VariableScope(), RunMode.SYNC))
                .hasMessageContaining("环");
    }
}
