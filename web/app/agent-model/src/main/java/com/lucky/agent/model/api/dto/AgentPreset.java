package com.lucky.agent.model.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 预设：与「模型端点」并列的第二类全局设置，承载用户可在 Web 上直接调节的
 * Agent 运行参数与自定义提示词，落盘于 {@code <frameworkRoot>/settings.json}。
 *
 * <p>设计取舍（§五-5-2）：<b>只保留能真实影响运行时的参数</b>，不设「假参数」。
 * 每个字段都对应一处真实接线点：</p>
 * <ul>
 *     <li>{@code maxSteps} → {@code core.act-max-steps}（ACT 阶段步数上限）</li>
 *     <li>{@code subagentEnabled}/{@code subagentMaxConcurrency} → {@code core.subagent-*}（多 Agent 派发）</li>
 *     <li>{@code verificationEnabled} → {@code core.verification-enabled}（客观验证链）</li>
 *     <li>{@code contextThreshold} → {@code model.context-threshold}（上下文压缩触发比例）</li>
 *     <li>{@code autoRetry} → {@code core.orchestrator-max-retries}（0 表示不重试）</li>
 *     <li>{@code systemPrompt} → 追加进 system prompt 的自定义指令段</li>
 *     <li>{@code extra} → 预留扩展位，未知键原样保留，避免旧版本读新配置时丢字段</li>
 * </ul>
 *
 * <p>未设置的字段为 {@code null}，表示「沿用 application.yml 默认值」，
 * 由 {@code PresetResolver} 与 {@code CoreProperties} 合成为最终生效值。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentPreset {

    /** ACT 阶段最大步数（null=沿用 yml）。 */
    private Integer maxSteps;
    /** 是否启用多 Agent / 子代理（null=沿用 yml）。 */
    private Boolean subagentEnabled;
    /** 子代理并行上限。 */
    private Integer subagentMaxConcurrency;
    /** 是否启用客观验证链（null=沿用 yml）。 */
    private Boolean verificationEnabled;
    /** 上下文压缩触发阈值（0~1）。 */
    private Double contextThreshold;
    /** 失败步骤自动重试（null=沿用 yml；false 表示不重试）。 */
    private Boolean autoRetry;
    /** 自定义系统提示词段（追加在基座与人格之后、规则之前）。 */
    private String systemPrompt;
    /** 扩展位：未知键原样保留，避免跨版本丢配置。 */
    private Map<String, Object> extra;

    public AgentPreset() {
        this.extra = new LinkedHashMap<>();
    }

    /** 全默认预设（全部字段为 null，等价于「完全沿用 yml」）。 */
    public static AgentPreset empty() {
        return new AgentPreset();
    }

    /** 复制副本（避免外部直接修改内部对象）。 */
    public AgentPreset copy() {
        AgentPreset p = new AgentPreset();
        p.maxSteps = maxSteps;
        p.subagentEnabled = subagentEnabled;
        p.subagentMaxConcurrency = subagentMaxConcurrency;
        p.verificationEnabled = verificationEnabled;
        p.contextThreshold = contextThreshold;
        p.autoRetry = autoRetry;
        p.systemPrompt = systemPrompt;
        p.extra = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
        return p;
    }

    public Integer maxSteps() {
        return maxSteps;
    }

    public void maxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Boolean subagentEnabled() {
        return subagentEnabled;
    }

    public void subagentEnabled(Boolean subagentEnabled) {
        this.subagentEnabled = subagentEnabled;
    }

    public Integer subagentMaxConcurrency() {
        return subagentMaxConcurrency;
    }

    public void subagentMaxConcurrency(Integer subagentMaxConcurrency) {
        this.subagentMaxConcurrency = subagentMaxConcurrency;
    }

    public Boolean verificationEnabled() {
        return verificationEnabled;
    }

    public void verificationEnabled(Boolean verificationEnabled) {
        this.verificationEnabled = verificationEnabled;
    }

    public Double contextThreshold() {
        return contextThreshold;
    }

    public void contextThreshold(Double contextThreshold) {
        this.contextThreshold = contextThreshold;
    }

    public Boolean autoRetry() {
        return autoRetry;
    }

    public void autoRetry(Boolean autoRetry) {
        this.autoRetry = autoRetry;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public void systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public void extra(Map<String, Object> extra) {
        this.extra = extra == null ? new LinkedHashMap<>() : extra;
    }
}
