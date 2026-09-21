package com.lucky.agent.model.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局 Agent 设置（用户工作空间根目录 {@code settings.json}）。
 *
 * <p>承载三类配置，彼此独立、可分别更新：</p>
 * <ol>
 *     <li>{@link #models} — 模型端点列表（按列表顺序决定主端点优先级）；</li>
 *     <li>{@link #inferenceDepth} — 全局推理深度，映射为 OpenAI {@code reasoning_effort}
 *         与 Anthropic {@code thinking} 预算；</li>
 *     <li>{@link #agentPreset} — Agent 预设（步数上限 / 子代理 / 验证开关 / 压缩阈值 / 自定义提示词），
 *         未设置字段沿用 {@code application.yml} 默认值。</li>
 * </ol>
 * <p>{@link #views()} 是端点列表的视图副本：调用方只能通过它做增量修改，
 * 无法整体替换列表，避免与 preset 的持久化互相覆盖。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentSettings {

    /** 全局推理深度（默认 {@link InferenceDepth#BALANCED}）。 */
    private InferenceDepth inferenceDepth = InferenceDepth.defaultValue();

    /** 模型端点列表。 */
    private List<ModelConfig> models;

    /** Agent 预设（可空；空表示全部沿用 yml 默认值）。 */
    private AgentPreset agentPreset;

    public static AgentSettings of(InferenceDepth inferenceDepth, List<ModelConfig> models) {
        AgentSettings settings = new AgentSettings();
        settings.inferenceDepth(inferenceDepth);
        settings.models = models;
        return settings;
    }

    /** 空设置：默认推理深度 + 空模型列表 + 空预设。 */
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

    /**
     * 端点列表视图：优先返回真实列表；未初始化时初始化为空列表后返回。
     *
     * <p>调用方（{@code EndpointAccessCenter}）持此引用做 add/set/remove，
     * 因引用与 {@link #models} 同一对象，改动会随 {@code store.save(settings)} 一起落盘，
     * 同时不触碰 {@link #agentPreset}。</p>
     */
    @JsonIgnore
    public List<ModelConfig> views() {
        if (models == null) {
            models = new ArrayList<>();
        }
        return models;
    }

    @JsonProperty("agentPreset")
    public AgentPreset agentPreset() {
        return agentPreset;
    }

    @JsonProperty("agentPreset")
    public void agentPreset(AgentPreset agentPreset) {
        this.agentPreset = agentPreset;
    }
}
