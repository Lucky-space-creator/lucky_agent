package com.lucky.agent.workflow.domain;

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
 */
public record NodeDef(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("type") WorkflowNodeType type,
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("inputs") List<InputMapping> inputs,
        @JsonProperty("outputs") List<OutputMapping> outputs) {

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
