package com.lucky.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.domain.NodeInstance;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.engine.InputMapping;
import com.lucky.agent.workflow.repository.FileWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.FileWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落盘持久化回归测试：验证工作流定义与运行实例能跨「进程重启」往返。
 *
 * <p>回归背景：两个仓储此前默认走内存实现，重启后定义与运行记录全部消失 ——
 * 前端列表为空、已建流程触发报「工作流不存在」（用户可直接感知的数据丢失）。
 * 本测试用一个<b>全新的仓储实例</b>读取同一目录来模拟重启，锁定持久化语义。</p>
 *
 * <p>使用裸 {@link ObjectMapper}（而非 Spring 托管的实例）：这样一旦有人移除了
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}，计算型 getter
 * （{@code autoTrigger} / {@code conditional}）就会让往返立即失败，而非依赖
 * 宿主的宽松配置侥幸通过。</p>
 */
class WorkflowPersistenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void workflowDefShouldSurviveRestart(@TempDir Path dir) {
        WorkflowDef def = new WorkflowDef("wf-persist", "持久化验证", "跨重启", 1,
                List.of(new com.lucky.agent.workflow.domain.NodeDef("start", "开始",
                                WorkflowNodeType.START, Map.of(), List.of(), List.of()),
                        new com.lucky.agent.workflow.domain.NodeDef("gen", "生成",
                                WorkflowNodeType.LLM, Map.of("prompt", "主题：${topic}"),
                                List.of(new InputMapping("topic", "topic")), List.of())),
                List.of(new com.lucky.agent.workflow.domain.EdgeDef(null, "start", "gen", null)),
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, 111L, 222L);

        new FileWorkflowRepository(dir, mapper).save(def);

        // 模拟重启：新建仓储实例读同一目录
        FileWorkflowRepository reopened = new FileWorkflowRepository(dir, mapper);
        Optional<WorkflowDef> loaded = reopened.findById("wf-persist");

        assertThat(loaded).isPresent();
        WorkflowDef got = loaded.get();
        assertThat(got.id()).isEqualTo("wf-persist");
        assertThat(got.name()).isEqualTo("持久化验证");
        assertThat(got.version()).isEqualTo(1);
        assertThat(got.enabled()).isTrue();
        assertThat(got.createdAt()).isEqualTo(111L);
        assertThat(got.updatedAt()).isEqualTo(222L);
        assertThat(got.trigger().type().name()).isEqualTo("MANUAL");
        assertThat(got.nodes()).hasSize(2);

        // 节点 config 与连接器必须原样保留（画布参数编辑的回填依赖它）
        var gen = got.nodes().stream().filter(n -> n.id().equals("gen")).findFirst().orElseThrow();
        assertThat(gen.type()).isEqualTo(WorkflowNodeType.LLM);
        assertThat(gen.config()).containsEntry("prompt", "主题：${topic}");
        assertThat(gen.inputs()).containsExactly(new InputMapping("topic", "topic"));

        // 边的 id 由紧凑构造按 source->target 补齐，往返后应保持
        assertThat(got.edges()).hasSize(1);
        assertThat(got.edges().get(0).id()).isEqualTo("start->gen");

        assertThat(reopened.findAll()).hasSize(1);
        assertThat(reopened.deleteById("wf-persist")).isTrue();
        assertThat(reopened.findAll()).isEmpty();
    }

    @Test
    void nodeCanvasPositionShouldSurviveRestart(@TempDir Path dir) {
        // 画布坐标属可视化元数据：丢了不报错，但用户每次打开都要重新摆一遍布局，
        // 所以必须显式锁定「坐标随定义落盘」这一行为。
        var placed = new com.lucky.agent.workflow.domain.NodeDef("gen", "生成",
                WorkflowNodeType.LLM, Map.of("prompt", "hi"), List.of(), List.of(),
                new com.lucky.agent.workflow.domain.NodePosition(320.0, 144.0));
        // 不带坐标的节点（引擎装配/旧数据）应保持 position == null，不应被补成 (0,0)
        var unplaced = new com.lucky.agent.workflow.domain.NodeDef("end", "结束",
                WorkflowNodeType.END, Map.of(), List.of(), List.of());

        WorkflowDef def = new WorkflowDef("wf-pos", "坐标验证", null, 1,
                List.of(placed, unplaced),
                List.of(new com.lucky.agent.workflow.domain.EdgeDef(null, "gen", "end", null)),
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, 1L, 2L);

        new FileWorkflowRepository(dir, mapper).save(def);

        WorkflowDef got = new FileWorkflowRepository(dir, mapper).findById("wf-pos").orElseThrow();
        var gen = got.nodes().stream().filter(n -> n.id().equals("gen")).findFirst().orElseThrow();
        var end = got.nodes().stream().filter(n -> n.id().equals("end")).findFirst().orElseThrow();

        assertThat(gen.position()).isNotNull();
        assertThat(gen.position().x()).isEqualTo(320.0);
        assertThat(gen.position().y()).isEqualTo(144.0);
        assertThat(end.position()).isNull();
    }

    @Test
    void workflowInstanceShouldSurviveRestart(@TempDir Path dir) {
        WorkflowInstance inst = new WorkflowInstance("wf-persist", "持久化验证");
        inst.getVariables().set("topic", "退款");
        NodeInstance node = inst.node("gen", "生成", WorkflowNodeType.LLM);
        node.markRunning();
        VariableScope out = new VariableScope();
        out.set("text", "已受理");
        node.markCompleted(out);
        inst.setCurrentNodeId("gen");
        inst.markCompleted();

        new FileWorkflowInstanceRepository(dir, mapper).save(inst);

        // 模拟重启
        FileWorkflowInstanceRepository reopened = new FileWorkflowInstanceRepository(dir, mapper);
        Optional<WorkflowInstance> loaded = reopened.findById(inst.getInstanceId());

        assertThat(loaded).isPresent();
        WorkflowInstance got = loaded.get();
        assertThat(got.getInstanceId()).isEqualTo(inst.getInstanceId());
        assertThat(got.getWorkflowId()).isEqualTo("wf-persist");
        assertThat(got.getWorkflowName()).isEqualTo("持久化验证");
        assertThat(got.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(got.getCurrentNodeId()).isEqualTo("gen");
        assertThat(got.getStartedAt()).isEqualTo(inst.getStartedAt());
        assertThat(got.getEndedAt()).isGreaterThan(0L);

        // 变量作用域以 {"data":{...}} 形态往返，必须逐项还原
        assertThat(got.getVariables().get("topic")).isEqualTo("退款");

        // 节点实例的终态、类型与输出必须还原
        assertThat(got.getNodeInstances()).containsKey("gen");
        NodeInstance gotNode = got.getNodeInstances().get("gen");
        assertThat(gotNode.getType()).isEqualTo(WorkflowNodeType.LLM);
        assertThat(gotNode.getStatus()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(gotNode.getOutput().get("text")).isEqualTo("已受理");

        assertThat(reopened.findAll()).hasSize(1);
    }

    @Test
    void instanceRepositoryShouldPruneBeyondRetentionCap(@TempDir Path dir) throws IOException {
        int cap = 3;
        FileWorkflowInstanceRepository repo = new FileWorkflowInstanceRepository(dir, mapper, cap);

        // 依次写入 5 个实例，并显式设置递增 mtime，使淘汰顺序确定（不依赖文件系统时间精度）
        for (int i = 1; i <= 5; i++) {
            WorkflowInstance inst = new WorkflowInstance("wf", "w" + i);
            repo.save(inst);
            Files.setLastModifiedTime(dir.resolve(inst.getInstanceId() + ".json"),
                    FileTime.fromMillis(1_700_000_000_000L + i * 1000L));
        }

        List<WorkflowInstance> kept = repo.findAll();
        assertThat(kept).hasSize(cap);
        // 保留最新 3 个（按 mtime 降序：w5 / w4 / w3），w1、w2 被淘汰
        assertThat(kept).extracting(WorkflowInstance::getWorkflowName)
                .containsExactly("w5", "w4", "w3");
    }

    @Test
    void instanceRepositoryShouldSkipCorruptFileInsteadOfFailing(@TempDir Path dir) throws IOException {
        FileWorkflowInstanceRepository repo = new FileWorkflowInstanceRepository(dir, mapper);
        WorkflowInstance good = new WorkflowInstance("wf", "正常");
        repo.save(good);
        Files.writeString(dir.resolve("broken.json"), "{ this is not json");

        // 坏文件属旁路记录，不应让整个列表接口失败
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findAll().get(0).getWorkflowName()).isEqualTo("正常");
        assertThat(repo.findById("broken")).isEmpty();
    }
}
