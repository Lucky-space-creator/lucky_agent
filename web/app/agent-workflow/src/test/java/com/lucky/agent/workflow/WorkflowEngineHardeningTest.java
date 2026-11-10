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
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.repository.InMemoryWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowRepository;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流引擎加固（台账 #32–#35）。
 *
 * <ul>
 *   <li>#33：失败事件必须先落盘再发，否则前端回查拿到 RUNNING 旧快照。</li>
 *   <li>#34：不可达节点不能停在 PENDING 而整体报 COMPLETED。</li>
 *   <li>#35：SUBFLOW 无递归防护会一路栈溢出，且每帧 finally 落盘 RUNNING 僵尸实例。</li>
 * </ul>
 */
class WorkflowEngineHardeningTest {

    private final MappingEvaluator mappingEvaluator = new MappingEvaluator();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator(mappingEvaluator);
    private final WorkflowEventBus eventBus = new WorkflowEventBus();

    // ---------- #34 不可达节点 ----------

    @Test
    @DisplayName("#34 不可达节点必须标 SKIPPED，不能停在 PENDING 而整体报成功")
    void unreachableNodeIsMarkedSkipped() {
        // start → end 主链，另加一个没接任何边的 LLM 节点（DagCompiler 不校验可达性）
        NodeDef start = node("start", WorkflowNodeType.START);
        NodeDef end = node("end", WorkflowNodeType.END);
        NodeDef lonely = node("lonely", WorkflowNodeType.LLM);
        WorkflowDef def = def("wf-unreachable",
                List.of(start, end, lonely),
                List.of(new EdgeDef("e1", "start", "end", null)));

        WorkflowInstance instance = newEngine(new InMemoryWorkflowRepository(),
                new InMemoryWorkflowInstanceRepository()).run(def, new VariableScope(), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.nodeOf("end").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(instance.nodeOf("lonely")).as("不可达节点必须被收尾").isNotNull();
        assertThat(instance.nodeOf("lonely").getStatus()).isEqualTo(NodeStatus.SKIPPED);
    }

    // ---------- #33 失败事件顺序 ----------

    @Test
    @DisplayName("#33 WORKFLOW_FAILED 事件发出时，实例必须已经落盘为 FAILED")
    void failureIsPersistedBeforeEventIsPublished() {
        List<String> timeline = new ArrayList<>();
        RecordingInstanceRepository instanceRepo = new RecordingInstanceRepository(timeline);
        eventBus.subscribe(e -> timeline.add("event:" + e.type()));

        // 直接让 execute 抛异常，走 safeExecute 的 catch → markFailed 路径
        ThrowingEngine engine = new ThrowingEngine(mappingEvaluator, conditionEvaluator, eventBus,
                executors(), new InMemoryWorkflowRepository(), instanceRepo);
        NodeDef start = node("start", WorkflowNodeType.START);
        NodeDef end = node("end", WorkflowNodeType.END);
        WorkflowDef def = def("wf-throw", List.of(start, end),
                List.of(new EdgeDef("e1", "start", "end", null)));

        engine.run(def, new VariableScope(), RunMode.SYNC);

        int persistedFailed = timeline.indexOf("save:FAILED");
        int eventFailed = timeline.indexOf("event:" + WorkflowEventType.WORKFLOW_FAILED);
        assertThat(persistedFailed).as("必须有一次 FAILED 落盘").isGreaterThanOrEqualTo(0);
        assertThat(eventFailed).isGreaterThan(0);
        assertThat(persistedFailed)
                .as("落盘必须先于事件，否则前端回查拿到 RUNNING 旧快照")
                .isLessThan(eventFailed);
    }

    // ---------- #35 子流程环 ----------

    @Test
    @DisplayName("#35 A→B→A 子流程循环必须被拒绝，不能栈溢出")
    void subflowCycleIsRejectedWithoutStackOverflow() {
        InMemoryWorkflowRepository defRepo = new InMemoryWorkflowRepository();
        defRepo.save(subflowWorkflow("wf-a", "wf-b"));
        defRepo.save(subflowWorkflow("wf-b", "wf-a"));

        WorkflowInstance instance = newEngine(defRepo, new InMemoryWorkflowInstanceRepository())
                .run(defRepo.findById("wf-a").orElseThrow(), new VariableScope(), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(instance.getError()).contains("循环引用");
    }

    @Test
    @DisplayName("#35 自引用（A→A）同样被拦")
    void selfReferencingSubflowIsRejected() {
        InMemoryWorkflowRepository defRepo = new InMemoryWorkflowRepository();
        defRepo.save(subflowWorkflow("wf-self", "wf-self"));

        WorkflowInstance instance = newEngine(defRepo, new InMemoryWorkflowInstanceRepository())
                .run(defRepo.findById("wf-self").orElseThrow(), new VariableScope(), RunMode.SYNC);

        assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(instance.getError()).contains("循环引用");
    }

    // ---------- 辅助 ----------

    private static NodeDef node(String id, WorkflowNodeType type) {
        return new NodeDef(id, id, type, Map.of(), List.of(), List.of());
    }

    private static WorkflowDef def(String id, List<NodeDef> nodes, List<EdgeDef> edges) {
        return new WorkflowDef(id, id, null, 1, nodes, edges, TriggerDef.manual(), true, null, null);
    }

    /** start → SUBFLOW(subWorkflowId) → end。 */
    private static WorkflowDef subflowWorkflow(String id, String subId) {
        NodeDef start = node("start", WorkflowNodeType.START);
        NodeDef sub = new NodeDef("sub", "子流程", WorkflowNodeType.SUBFLOW,
                Map.of("subWorkflowId", subId), List.of(), List.of());
        NodeDef end = node("end", WorkflowNodeType.END);
        return def(id, List.of(start, sub, end),
                List.of(new EdgeDef("e1", "start", "sub", null), new EdgeDef("e2", "sub", "end", null)));
    }

    private List<NodeExecutor> executors() {
        return List.of(new StartNodeExecutor(), new EndNodeExecutor(),
                new LlmNodeExecutor(mappingEvaluator), new ToolNodeExecutor(),
                new ConditionNodeExecutor(), new CodeNodeExecutor(mappingEvaluator),
                new SubflowNodeExecutor());
    }

    private WorkflowEngine newEngine(WorkflowRepository defRepo, WorkflowInstanceRepository instanceRepo) {
        return new WorkflowEngine(new DagCompiler(), new WorkflowStateMachine(), mappingEvaluator,
                conditionEvaluator, eventBus, executors(),
                new NoopLlmAdapter(), new NoopToolAdapter(),
                new LocalSandboxAdapter(false, 5000, null), defRepo, instanceRepo, null);
    }

    /** 记录每次 save 时的状态，与事件交错形成时间线，用于验证「落盘先于事件」。 */
    private static final class RecordingInstanceRepository implements WorkflowInstanceRepository {
        private final List<WorkflowInstance> instances = new ArrayList<>();
        private final List<String> timeline;

        RecordingInstanceRepository(List<String> timeline) {
            this.timeline = timeline;
        }

        @Override
        public void save(WorkflowInstance instance) {
            instances.add(instance);
            timeline.add("save:" + instance.getStatus());
        }

        @Override
        public Optional<WorkflowInstance> findById(String instanceId) {
            return instances.stream()
                    .filter(i -> instanceId.equals(i.getInstanceId()))
                    .findFirst();
        }

        @Override
        public List<WorkflowInstance> findAll() {
            return List.copyOf(instances);
        }
    }

    /** 故意在 execute 里抛异常的引擎，用于验证 safeExecute 的失败收尾顺序。 */
    private static final class ThrowingEngine extends WorkflowEngine {
        ThrowingEngine(MappingEvaluator mappingEvaluator, ConditionEvaluator conditionEvaluator,
                       WorkflowEventBus eventBus, List<NodeExecutor> executors,
                       WorkflowRepository defRepo, WorkflowInstanceRepository instanceRepo) {
            super(new DagCompiler(), new WorkflowStateMachine(), mappingEvaluator,
                    conditionEvaluator, eventBus, executors,
                    new NoopLlmAdapter(), new NoopToolAdapter(),
                    new LocalSandboxAdapter(false, 5000, null), defRepo, instanceRepo, null);
        }

        @Override
        protected void execute(WorkflowDef definition, WorkflowInstance instance) {
            throw new IllegalStateException("boom");
        }
    }
}
