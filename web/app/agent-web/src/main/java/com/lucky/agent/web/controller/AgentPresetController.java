package com.lucky.agent.web.controller;

import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.model.api.dto.AgentPreset;
import com.lucky.agent.model.support.endpoint.EndpointAccessCenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 预设接口：Web 端直接读写 Agent 运行参数与自定义提示词。
 *
 * <p>承接五项功能优化 §五-5-2。响应同时返回 <b>preset</b>（用户配置，可能大量为 null）
 * 与 <b>effective</b>（与 {@code CoreProperties} 合成后的最终生效值），
 * 让前端把「沿用默认」与「已自定义」两种状态区分展示，避免出现「改了看不出是否生效」的假参数。</p>
 *
 * <p>磁盘读写为阻塞 I/O，offload 到 {@code boundedElastic}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/preset")
public class AgentPresetController {

    private final EndpointAccessCenter accessCenter;
    private final ObjectProvider<CoreProperties> coreProperties;
    private final double ymlContextThreshold;

    public AgentPresetController(EndpointAccessCenter accessCenter,
                                 ObjectProvider<CoreProperties> coreProperties,
                                 @Value("${model.context-threshold:0.9}") double ymlContextThreshold) {
        this.accessCenter = accessCenter;
        this.coreProperties = coreProperties;
        this.ymlContextThreshold = ymlContextThreshold;
    }

    /** 读取 Agent 预设（含合成后的生效值）。 */
    @GetMapping
    public Mono<Map<String, Object>> get() {
        return Mono.fromCallable(this::snapshot).subscribeOn(Schedulers.boundedElastic());
    }

    /** 保存 Agent 预设（整体覆盖；null 字段表示沿用 yml 默认值）。 */
    @PostMapping
    public Mono<Map<String, Object>> save(@RequestBody AgentPreset preset) {
        return Mono.fromCallable(() -> {
            accessCenter.setAgentPreset(preset);
            return snapshot();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 恢复出厂：清空预设（全部沿用 yml 默认值）。 */
    @PostMapping("/reset")
    public Mono<Map<String, Object>> reset() {
        return Mono.fromCallable(() -> {
            accessCenter.setAgentPreset(AgentPreset.empty());
            return snapshot();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 组装响应：{@code preset} 为用户配置，{@code effective} 为合成后的生效值。 */
    private Map<String, Object> snapshot() {
        AgentPreset preset = accessCenter.agentPreset();
        CoreProperties core = coreProperties.getIfAvailable();

        int effMaxSteps = preset.maxSteps() != null ? preset.maxSteps()
                : (core != null ? core.actMaxSteps() : 30);
        boolean effSubagent = preset.subagentEnabled() != null ? preset.subagentEnabled()
                : (core != null && core.subagentEnabled());
        int effConcurrency = preset.subagentMaxConcurrency() != null ? preset.subagentMaxConcurrency()
                : (core != null ? core.subagentMaxConcurrency() : 4);
        boolean effVerification = preset.verificationEnabled() != null ? preset.verificationEnabled()
                : (core == null || core.verificationEnabled());
        double effThreshold = preset.contextThreshold() != null ? preset.contextThreshold() : ymlContextThreshold;
        boolean effAutoRetry = preset.autoRetry() != null ? preset.autoRetry()
                : (core == null || core.orchestratorMaxRetries() > 0);

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("maxSteps", effMaxSteps);
        effective.put("subagentEnabled", effSubagent);
        effective.put("subagentMaxConcurrency", effConcurrency);
        effective.put("verificationEnabled", effVerification);
        effective.put("contextThreshold", effThreshold);
        effective.put("autoRetry", effAutoRetry);
        effective.put("orchestratorMode", core == null ? "reactor" : core.orchestratorMode());

        // 来源标记：true=用户在预设中自定义，false=沿用 yml 默认
        Map<String, Object> overridden = new LinkedHashMap<>();
        overridden.put("maxSteps", preset.maxSteps() != null);
        overridden.put("subagentEnabled", preset.subagentEnabled() != null);
        overridden.put("subagentMaxConcurrency", preset.subagentMaxConcurrency() != null);
        overridden.put("verificationEnabled", preset.verificationEnabled() != null);
        overridden.put("contextThreshold", preset.contextThreshold() != null);
        overridden.put("autoRetry", preset.autoRetry() != null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preset", preset);
        out.put("effective", effective);
        out.put("overridden", overridden);
        return out;
    }
}
