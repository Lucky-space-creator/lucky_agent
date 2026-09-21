package com.lucky.agent.core.config;

import com.lucky.agent.model.api.dto.AgentPreset;
import com.lucky.agent.model.support.endpoint.EndpointAccessCenter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 预设解析器：把用户在设置页保存的 {@link AgentPreset} 叠加到
 * {@link CoreProperties}（{@code application.yml} 默认值）之上，得到真正生效的运行参数。
 *
 * <p>这是「假参数接线」的关键一环：之前设置页的参数只是前端本地 ref，
 * 改了不起作用；现在所有运行时读取都经由此处解析，<b>预设优先、yml 兜底</b>，
 * 且不依赖重启（每次调用实时读取内存快照，保存即生效）。</p>
 *
 * <p>读取路径：{@code EndpointAccessCenter}（settings.json 的内存镜像，端点与预设同源）。
 * 用 {@link ObjectProvider} 懒取，避免与 {@code EndpointAccessCenter} 形成装配环。</p>
 */
@Slf4j
@Component
public class PresetResolver {

    private final ObjectProvider<EndpointAccessCenter> accessCenter;
    private final CoreProperties coreProperties;
    private final double ymlContextThreshold;
    private final long optionsTimeoutSec;

    public PresetResolver(ObjectProvider<EndpointAccessCenter> accessCenter,
                          CoreProperties coreProperties,
                          @Value("${model.context-threshold:0.9}") double ymlContextThreshold,
                          @Value("${core.options-timeout-sec:300}") long optionsTimeoutSec) {
        this.accessCenter = accessCenter;
        this.coreProperties = coreProperties;
        this.ymlContextThreshold = ymlContextThreshold;
        this.optionsTimeoutSec = optionsTimeoutSec;
    }

    @PostConstruct
    void logResolved() {
        log.info("Agent 预设解析器就绪：yml 默认 actMaxSteps={}、verification={}、contextThreshold={}",
                coreProperties.actMaxSteps(), coreProperties.verificationEnabled(), ymlContextThreshold);
    }

    /** ACT 阶段最大步数：预设优先，否则 yml 默认。 */
    public int actMaxSteps() {
        AgentPreset p = preset();
        Integer v = p == null ? null : p.maxSteps();
        return (v != null && v > 0) ? v : coreProperties.actMaxSteps();
    }

    /** 是否启用多 Agent / 子代理：预设优先，否则 yml 默认。 */
    public boolean subagentEnabled() {
        AgentPreset p = preset();
        Boolean v = p == null ? null : p.subagentEnabled();
        return v != null ? v : coreProperties.subagentEnabled();
    }

    /** 子代理并行上限。 */
    public int subagentMaxConcurrency() {
        AgentPreset p = preset();
        Integer v = p == null ? null : p.subagentMaxConcurrency();
        return (v != null && v > 0) ? v : coreProperties.subagentMaxConcurrency();
    }

    /** 是否启用客观验证链。 */
    public boolean verificationEnabled() {
        AgentPreset p = preset();
        Boolean v = p == null ? null : p.verificationEnabled();
        return v != null ? v : (coreProperties.verificationEnabled() == null || coreProperties.verificationEnabled());
    }

    /** 上下文压缩触发阈值（0~1）。 */
    public double contextThreshold() {
        AgentPreset p = preset();
        Double v = p == null ? null : p.contextThreshold();
        if (v == null || v <= 0 || v > 1) {
            return ymlContextThreshold;
        }
        return v;
    }

    /** 子任务失败重试上限（预设 autoRetry=false → 0，即不重试）。 */
    public int orchestratorMaxRetries() {
        AgentPreset p = preset();
        Boolean auto = p == null ? null : p.autoRetry();
        if (auto != null && !auto) {
            return 0;
        }
        return coreProperties.orchestratorMaxRetries();
    }

    /** 自定义系统提示词段（可空）。 */
    public String systemPrompt() {
        AgentPreset p = preset();
        String s = p == null ? null : p.systemPrompt();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 条件选择（options）挂起超时秒数：超时后由超时兜底逻辑按推荐项自动继续。
     *
     * <p>取 {@code core.options-timeout-sec}（默认 300 秒 = 5 分钟）；&lt;=0 表示不设超时（永久挂起）。</p>
     */
    public long optionsTimeoutSec() {
        return optionsTimeoutSec;
    }

    /** 当前预设快照；端点接入中心未就绪时返回 null（全部回退 yml）。 */
    private AgentPreset preset() {
        EndpointAccessCenter center = accessCenter.getIfAvailable();
        return center == null ? null : center.agentPreset();
    }
}
