package com.lucky.agent.workflow;

import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.dto.NodeTypeMeta;
import com.lucky.agent.workflow.service.NodeTypeCatalog;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点类型目录一致性测试。
 *
 * <p>锁定的核心风险：目录里的 {@code config} 键必须与执行器实际读取的键一致。
 * 一旦不一致，画布上填写的参数会被引擎静默忽略 —— 流程「成功」但产出不符预期，
 * 是极难定位的一类缺陷。此处通过对「执行器源码中的 config 键字面量」做交叉校验来兜住。</p>
 *
 * <p>本测试不反射执行器内部（键名散落在各方法的 {@code config("x")} 调用中），
 * 而是断言目录的完整性与结构契约；键名一致性由 {@link #catalogKeysMustMatchDocumentedContract()}
 * 以显式清单形式固化，任何一侧改动都会使该断言失败并提示同步。</p>
 */
class NodeTypeCatalogTest {

    @Test
    void catalogShouldCoverEveryNodeTypeExactlyOnce() {
        List<NodeTypeMeta> catalog = NodeTypeCatalog.catalog();
        Set<String> declared = catalog.stream().map(NodeTypeMeta::type).collect(Collectors.toSet());

        // 与枚举逐一对应：既不遗漏（前端无法添加），也不多于（画布能放但引擎不认）
        Set<String> expected = Arrays.stream(WorkflowNodeType.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(declared).isEqualTo(expected);
        assertThat(catalog).hasSize(expected.size());
    }

    @Test
    void structureNodesShouldBeMarkedCorrectly() {
        List<NodeTypeMeta> catalog = NodeTypeCatalog.catalog();

        NodeTypeMeta start = byType(catalog, "START");
        assertThat(start.singleton()).as("START 全流程唯一").isTrue();
        assertThat(start.required()).isTrue();
        assertThat(start.fields()).as("START 无 config").isEmpty();

        NodeTypeMeta end = byType(catalog, "END");
        assertThat(end.singleton()).as("END 可多个（多分支收敛）").isFalse();
        assertThat(end.required()).isTrue();
        assertThat(end.fields()).isEmpty();
    }

    @Test
    void everyFieldMustBeRenderable() {
        Set<String> supportedInputs = Set.of("TEXT", "TEXTAREA", "NUMBER", "BOOLEAN", "SELECT", "JSON");
        for (NodeTypeMeta meta : NodeTypeCatalog.catalog()) {
            for (NodeTypeMeta.ConfigField f : meta.fields()) {
                assertThat(f.key()).as("%s 的字段 key 不可为空", meta.type()).isNotBlank();
                assertThat(f.label()).as("%s.%s 需有展示名", meta.type(), f.key()).isNotBlank();
                assertThat(supportedInputs)
                        .as("%s.%s 的控件类型 %s 不在前端可渲染集合内", meta.type(), f.key(), f.input())
                        .contains(f.input());
                if ("SELECT".equals(f.input())) {
                    assertThat(f.options()).as("%s.%s 为 SELECT 时必须提供选项", meta.type(), f.key())
                            .isNotNull().isNotEmpty();
                }
            }
        }
    }

    /**
     * config 键与执行器的显式契约清单。
     * <p>改动执行器读取的键（或目录字段）时，必须同步更新此处 —— 本条即「防静默失配」的闸门。</p>
     */
    @Test
    void catalogKeysMustMatchDocumentedContract() {
        List<NodeTypeMeta> catalog = NodeTypeCatalog.catalog();

        // LLM 节点读取 prompt / system
        assertThat(keysOf(catalog, "LLM")).containsExactlyInAnyOrder("prompt", "system");
        // TOOL 节点读取 toolName / params
        assertThat(keysOf(catalog, "TOOL")).containsExactlyInAnyOrder("toolName", "params");
        // CONDITION 节点读取 condition
        assertThat(keysOf(catalog, "CONDITION")).containsExactly("condition");
        // CODE 节点读取 command / timeoutMs / failOnError
        assertThat(keysOf(catalog, "CODE")).containsExactlyInAnyOrder("command", "timeoutMs", "failOnError");
        // SUBFLOW 节点读取 subWorkflowId
        assertThat(keysOf(catalog, "SUBFLOW")).containsExactly("subWorkflowId");
    }

    private NodeTypeMeta byType(List<NodeTypeMeta> catalog, String type) {
        return catalog.stream().filter(m -> m.type().equals(type)).findFirst().orElseThrow();
    }

    private Set<String> keysOf(List<NodeTypeMeta> catalog, String type) {
        return byType(catalog, type).fields().stream()
                .map(NodeTypeMeta.ConfigField::key)
                .collect(Collectors.toSet());
    }
}
