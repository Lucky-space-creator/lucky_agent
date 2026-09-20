package com.lucky.agent.workflow;

import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.TriggerDef;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.CompiledWorkflow;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.exception.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DAG 编译：拓扑排序、起始节点识别、环检测与结构校验。 */
class DagCompilerTest {

    private final DagCompiler compiler = new DagCompiler();

    private NodeDef node(String id, WorkflowNodeType type) {
        return new NodeDef(id, id, type, Map.of(), List.of(), List.of());
    }

    @Test
    void shouldCompileValidDag() {
        WorkflowDef def = new WorkflowDef("wf", "合法流程", null, 1,
                List.of(node("start", WorkflowNodeType.START),
                        node("a", WorkflowNodeType.LLM),
                        node("end", WorkflowNodeType.END)),
                List.of(new EdgeDef("e1", "start", "a", null),
                        new EdgeDef("e2", "a", "end", null)),
                TriggerDef.manual(), true, null, null);

        CompiledWorkflow cw = compiler.compile(def);

        assertThat(cw.startNodeId()).isEqualTo("start");
        assertThat(cw.topologicalOrder()).containsExactly("start", "a", "end");
        assertThat(cw.outgoing().get("a")).hasSize(1);
    }

    @Test
    void shouldRejectCycle() {
        WorkflowDef def = new WorkflowDef("wf", "环形流程", null, 1,
                List.of(node("start", WorkflowNodeType.START),
                        node("a", WorkflowNodeType.LLM),
                        node("b", WorkflowNodeType.LLM),
                        node("end", WorkflowNodeType.END)),
                List.of(new EdgeDef("e1", "start", "a", null),
                        new EdgeDef("e2", "a", "b", null),
                        new EdgeDef("e3", "b", "a", null),
                        new EdgeDef("e4", "a", "end", null)),
                TriggerDef.manual(), true, null, null);

        assertThatThrownBy(() -> compiler.compile(def))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("环");
    }

    @Test
    void shouldRejectMissingStart() {
        WorkflowDef def = new WorkflowDef("wf", "无起点", null, 1,
                List.of(node("a", WorkflowNodeType.LLM), node("end", WorkflowNodeType.END)),
                List.of(new EdgeDef("e1", "a", "end", null)),
                TriggerDef.manual(), true, null, null);

        assertThatThrownBy(() -> compiler.compile(def))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("START");
    }
}
