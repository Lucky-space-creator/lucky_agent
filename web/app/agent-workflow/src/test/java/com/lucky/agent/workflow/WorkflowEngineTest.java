package com.lucky.agent.workflow;

import com.lucky.agent.workflow.adapter.LocalSandboxAdapter;
import com.lucky.agent.workflow.adapter.NoopLlmAdapter;
import com.lucky.agent.workflow.adapter.NoopToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.engine.ConditionEvaluator;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.engine.MappingEvaluator;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.WorkflowEngine;
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
import com.lucky.agent.workflow.repository.InMemoryWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作流引擎端到端测试：定义 → 编译 → 执行 → 状态流转 → 条件分支。 */
class WorkflowEngineTest {

    private final MappingEvaluator mappingEvaluator = new MappingEvaluator();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator(mappingEvaluator);
    private final WorkflowEventBus eventBus = new WorkflowEventBus();

    private WorkflowEngine newEngine() {
        List<NodeExecutor> executors = List.of(
                new StartNodeExecutor(),
                new EndNodeExecutor(),
                new LlmNodeExecutor(mappingEvaluator),
                new ToolNodeExecutor(),
                new ConditionNodeExecutor(),
                new CodeNodeExecutor(mappingEvaluator),
                new SubflowNodeExecutor());
        return new WorkflowEngine(
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
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, null, null);
    }

    @Test
    void shouldRunHighScoreBranch() {
        WorkflowEngine engine = newEngine();
        List<WorkflowEvent> events = new ArrayList<>();
        eventBus.subscribe(events::add);

        WorkflowInstance instance = engine.run(branchWorkflow(),
                new VariableScope(Map.of("topic", "退款", "score", 80)), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.nodeOf("classify").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("approve").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("reject").getStatus()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(String.valueOf(instance.nodeOf("classify").getOutput().get("text")))
                .contains("noop-llm").contains("退款");
        assertThat(events).anyMatch(e -> e.type() == com.lucky.agent.workflow.event.WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(events).anyMatch(e -> e.type() == com.lucky.agent.workflow.event.WorkflowEventType.NODE_SKIPPED);
    }

    @Test
    void shouldRunLowScoreBranch() {
        WorkflowEngine engine = newEngine();
        WorkflowInstance instance = engine.run(branchWorkflow(),
                new VariableScope(Map.of("topic", "咨询", "score", 30)), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.nodeOf("reject").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("approve").getStatus()).isEqualTo(NodeStatus.SKIPPED);
    }

    @Test
    void shouldFailFastWhenNodeFails() {
        // 代码节点在沙箱关闭时返回失败 → 工作流 FAILED
        NodeDef start = new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of());
        NodeDef code = new NodeDef("code", "执行命令", WorkflowNodeType.CODE,
                Map.of("command", "echo hi"), List.of(), List.of());
        NodeDef end = new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of());
        WorkflowDef def = new WorkflowDef("wf-code", "代码工作流", null, 1,
                List.of(start, code, end),
                List.of(new EdgeDef("e1", "start", "code", null), new EdgeDef("e2", "code", "end", null)),
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, null, null);

        WorkflowInstance instance = newEngine().run(def, new VariableScope(), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(instance.nodeOf("code").getStatus()).isEqualTo(NodeStatus.FAILED);
        assertThat(instance.nodeOf("end")).isNull();
    }
}
