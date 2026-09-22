package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.InputMapping;
import com.lucky.agent.workflow.engine.OutputMapping;

import java.util.List;
import java.util.Map;

/**
 * 流程节点定义（静态，属于工作流定义的一部分）。
 * <p>节点通过 {@link #config} 携带类型相关参数：
 * <ul>
 *   <li>LLM：prompt 模板（支持 {@code ${var}} 占位）</li>
 *   <li>TOOL：toolName + 参数映射</li>
 *   <li>CONDITION：condition 表达式（实际路由由出边 condition 决定，节点本身仅作占位）</li>
 *   <li>CODE：command / script</li>
 *   <li>SUBFLOW：subWorkflowId</li>
 * </ul>
 * 连接器（inputs/outputs）负责节点与全局作用域之间的变量映射。</p>
 *
 * <p>{@code position} 为画布坐标，纯可视化元数据，引擎不读；为 {@code null}
 * 表示该节点尚无人工布局（前端按拓扑自动分层摆放）。</p>
 */
public record NodeDef(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("type") WorkflowNodeType type,
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("inputs") List<InputMapping> inputs,
        @JsonProperty("outputs") List<OutputMapping> outputs,
        @JsonProperty("position") NodePosition position) {

    /**
     * 兼容构造：不携带画布坐标。
     *
     * <p>保留它可让既有构造点（引擎装配、单元测试）无需改动即可编译，
     * 同时把坐标这项可视化关注点隔离在画布链路内。</p>
     *
     * <p>显式标 {@code DISABLED}：Spring 托管的 ObjectMapper 注册了
     * ParameterNamesModule，若不声明禁用，本构造会被当成第二个「属性型 creator」，
     * 与 record 规范构造冲突（Conflicting property-based creators）。</p>
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public NodeDef(String id, String name, WorkflowNodeType type, Map<String, Object> config,
                   List<InputMapping> inputs, List<OutputMapping> outputs) {
        this(id, name, type, config, inputs, outputs, null);
    }

    public NodeDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("NodeDef.id 不能为空");
        }
        type = (type == null) ? WorkflowNodeType.LLM : type;
        config = (config == null) ? Map.of() : config;
        inputs = (inputs == null) ? List.of() : List.copyOf(inputs);
        outputs = (outputs == null) ? List.of() : List.copyOf(outputs);
    }

    public Object config(String key) {
        return config.get(key);
    }

    public String config(String key, String defaultValue) {
        Object v = config.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public int configInt(String key, int defaultValue) {
        Object v = config.get(key);
        if (v == null) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(v));
    }

    public boolean configBool(String key, boolean defaultValue) {
        Object v = config.get(key);
        if (v == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
