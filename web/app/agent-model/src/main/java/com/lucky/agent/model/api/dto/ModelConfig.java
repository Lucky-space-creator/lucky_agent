package com.lucky.agent.model.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 模型端点配置。
 *
 * <p>用户在本机框架目录的 {@code settings.json} 配置 {@code endpointUrl + apiKey + modelName}；
 * Key 以明文 JSON 存储（用户本机工作空间，零托管），后续直接读取。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelConfig {

    private String id;
    private String name;
    private String endpointUrl;
    private String modelName;
    /**
     * 上下文窗口（仅作压缩阈值参考，不强制模型行为）。
     * 未配置时为 0，调用侧回退安全默认；由模型自身能力决定，我们只参考。
     */
    private int contextWindow;
    /**
     * 温度（可为空）：仅在显式配置时发送，否则交给模型使用自身默认（不强制）。
     */
    private Double temperature;
    /**
     * 最大输出 token（可为空）：仅在显式配置时发送，否则交给模型使用自身默认（不强制）。
     */
    private Integer maxTokens;
    private boolean enabled = true;
    /** 主端点 / 备用端点。 */
    private String role = "main";

    /** 明文 Key（存于用户工作空间，本机优先零托管）。 */
    private String apiKey;

    /**
     * 是否已配置 Key（列表接口脱敏后 {@code apiKey} 恒为空串，界面据此区分「未配置 / 已配置留空不修改」）。
     * 仅作展示态标记，不参与模型调用。
     */
    private boolean keyConfigured;

    public static ModelConfig of(String name, String endpointUrl, String modelName) {
        ModelConfig c = new ModelConfig();
        c.id = UUID.randomUUID().toString();
        c.name = name;
        c.endpointUrl = endpointUrl;
        c.modelName = modelName;
        return c;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("id")
    public void id(String id) {
        this.id = id;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("name")
    public void name(String name) {
        this.name = name;
    }

    @JsonProperty("endpointUrl")
    public String endpointUrl() {
        return endpointUrl;
    }

    @JsonProperty("endpointUrl")
    public void endpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    @JsonProperty("modelName")
    public String modelName() {
        return modelName;
    }

    @JsonProperty("modelName")
    public void modelName(String modelName) {
        this.modelName = modelName;
    }

    @JsonProperty("contextWindow")
    public int contextWindow() {
        return contextWindow;
    }

    @JsonProperty("contextWindow")
    public void contextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    @JsonProperty("temperature")
    public Double temperature() {
        return temperature;
    }

    @JsonProperty("temperature")
    public void temperature(Double temperature) {
        this.temperature = temperature;
    }

    @JsonProperty("maxTokens")
    public Integer maxTokens() {
        return maxTokens;
    }

    @JsonProperty("maxTokens")
    public void maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    @JsonProperty("enabled")
    public boolean enabled() {
        return enabled;
    }

    @JsonProperty("enabled")
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    @JsonProperty("role")
    public String role() {
        return role;
    }

    @JsonProperty("role")
    public void role(String role) {
        this.role = role;
    }

    @JsonProperty("apiKey")
    public String apiKey() {
        return apiKey;
    }

    @JsonProperty("apiKey")
    public void apiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @JsonProperty("keyConfigured")
    public boolean keyConfigured() {
        return keyConfigured;
    }

    @JsonProperty("keyConfigured")
    public void keyConfigured(boolean keyConfigured) {
        this.keyConfigured = keyConfigured;
    }

    /** 深拷贝（含 apiKey），便于落盘加密/接口脱敏时修改副本而不污染原对象。 */
    public ModelConfig copy() {
        ModelConfig c = new ModelConfig();
        c.id = this.id;
        c.name = this.name;
        c.endpointUrl = this.endpointUrl;
        c.modelName = this.modelName;
        c.contextWindow = this.contextWindow;
        c.temperature = this.temperature;
        c.maxTokens = this.maxTokens;
        c.enabled = this.enabled;
        c.role = this.role;
        c.apiKey = this.apiKey;
        c.keyConfigured = this.keyConfigured;
        return c;
    }
}
