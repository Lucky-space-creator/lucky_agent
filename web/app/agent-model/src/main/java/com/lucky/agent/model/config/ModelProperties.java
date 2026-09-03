package com.lucky.agent.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-model 配置项。
 *
 * @param contextThreshold 上下文占用率自动压缩阈值（默认 90%）
 * @param healthProbeSec   探活周期（秒）
 * @param cipher           加密方式（PBKDF2）
 */
@ConfigurationProperties(prefix = "model")
public record ModelProperties(
        double contextThreshold,
        long healthProbeSec,
        String cipher) {

    public ModelProperties {
        if (contextThreshold <= 0 || contextThreshold > 1) {
            contextThreshold = 0.9;
        }
        if (healthProbeSec <= 0) {
            healthProbeSec = 60;
        }
        if (cipher == null || cipher.isBlank()) {
            cipher = "PBKDF2";
        }
    }
}
