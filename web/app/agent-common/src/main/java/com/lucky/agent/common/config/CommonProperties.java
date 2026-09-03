package com.lucky.agent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-common 配置项。
 *
 * @param cacheIsolation    Caffeine/缓存隔离维度
 * @param modelSemaphore    模型在途数默认上限
 * @param globalRateLimit   全局入口限流（每秒许可数）
 */
@ConfigurationProperties(prefix = "common")
public record CommonProperties(
        String cacheIsolation,
        int modelSemaphore,
        double globalRateLimit) {

    public CommonProperties {
        if (cacheIsolation == null || cacheIsolation.isBlank()) {
            cacheIsolation = "user+workspace";
        }
        if (modelSemaphore <= 0) {
            modelSemaphore = 16;
        }
        if (globalRateLimit <= 0) {
            globalRateLimit = 50;
        }
    }
}
