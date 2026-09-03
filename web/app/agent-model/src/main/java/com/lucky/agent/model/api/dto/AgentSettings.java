package com.lucky.agent.model.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局 Agent 设置（用户工作空间根目录 {@code settings.json}）。
 *
 * <p>仅承载模型端点列表与全局推理深度两项配置；推理深度为全局单一值，
 * 以 {@link InferenceDepth} 枚举存储（配置文件中存枚举名），接入模型请求时映射为
 * OpenAI {@code reasoning_effort} 与 Anthropic {@code thinking} 预算。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentSettings {

    /** 全局推理深度（默认 {@link InferenceDepth#BALANCED}）。 */
    private InferenceDepth inferenceDepth = InferenceDepth.defaultValue();

    /** 模型端点列表。 */
    private List<ModelConfig> models;

    public static AgentSettings of(InferenceDepth inferenceDepth, List<ModelConfig> models) {
        AgentSettings settings = new AgentSettings();
        settings.inferenceDepth(inferenceDepth);
        settings.models = models;
        return settings;
    }

    /** 空设置：默认推理深度 + 空模型列表。 */
    public static AgentSettings empty() {
        return of(InferenceDepth.defaultValue(), new ArrayList<>());
    }

    @JsonProperty("inferenceDepth")
    public InferenceDepth inferenceDepth() {
        return inferenceDepth;
    }

    @JsonProperty("inferenceDepth")
    public void inferenceDepth(InferenceDepth inferenceDepth) {
        this.inferenceDepth = inferenceDepth == null ? InferenceDepth.defaultValue() : inferenceDepth;
    }

    @JsonProperty("models")
    public List<ModelConfig> models() {
        return models;
    }

    @JsonProperty("models")
    public void models(List<ModelConfig> models) {
        this.models = models;
    }
}
