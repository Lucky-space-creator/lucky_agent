package com.lucky.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-memory 配置项。
 *
 * @param decayPerDay      低置信衰减系数（每日）
 * @param forgetThreshold  遗忘置信阈值
 * @param factConfidence   沉淀事实阈值
 * @param recallTopK       默认召回条数
 */
@ConfigurationProperties(prefix = "memory")
public record MemoryProperties(
        double decayPerDay,
        double forgetThreshold,
        double factConfidence,
        int recallTopK) {

    public MemoryProperties {
        if (decayPerDay <= 0 || decayPerDay > 1) {
            decayPerDay = 0.99;
        }
        if (forgetThreshold <= 0) {
            forgetThreshold = 0.3;
        }
        if (factConfidence <= 0) {
            factConfidence = 0.7;
        }
        if (recallTopK <= 0) {
            recallTopK = 5;
        }
    }
}
