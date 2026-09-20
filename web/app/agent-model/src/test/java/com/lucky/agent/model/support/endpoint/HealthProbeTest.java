package com.lucky.agent.model.support.endpoint;

import com.lucky.agent.model.api.dto.ModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * HealthProbe 面向用户的友好探测结果 单元测试（覆盖无需真实网络的校验分支）。
 */
class HealthProbeTest {

    private final HealthProbe probe = new HealthProbe();

    @Test
    void testProbeOnce_MissingUrlReportsFriendly() {
        HealthProbe.ProbeResult result = probe.probeOnce(new ModelConfig());
        assertFalse(result.healthy());
        assertEquals("端点 URL 未配置", result.message());
    }

    @Test
    void testProbeOnce_MissingKeyReportsFriendly() {
        ModelConfig config = ModelConfig.of("端点", "https://api.openai.com/v1/chat/completions", "gpt");
        HealthProbe.ProbeResult result = probe.probeOnce(config);
        assertFalse(result.healthy());
        assertEquals("API Key 未配置", result.message());
    }

    @Test
    void testProbeOnce_MalformedUrlReportsFriendly() {
        ModelConfig config = ModelConfig.of("端点", "http://not a url", "gpt");
        config.apiKey("sk-test");
        HealthProbe.ProbeResult result = probe.probeOnce(config);
        assertFalse(result.healthy());
        assertEquals("端点地址格式不正确，请检查 URL", result.message());
    }
}
