package com.lucky.agent.model.prompt;

/**
 * 提示词缓存运行明细（供状态面板查询）。
 *
 * @param totalPlans       累计计划次数
 * @param hits             静态前缀命中次数（缓存可复用）
 * @param misses           未命中/无静态前缀次数
 * @param distinctPrefixes 累计不同的静态前缀指纹数
 * @param inheritPlans     子代理继承父代理前缀的计划次数
 * @param coverageRatio    静态前缀占完整提示词的平均比例（0~1）
 * @param lastFingerprint  最近一次计划的静态前缀指纹
 * @param lastFullLength   最近一次完整提示词长度
 */
public record PromptCacheStatus(long totalPlans, long hits, long misses,
                                long distinctPrefixes, long inheritPlans,
                                double coverageRatio, String lastFingerprint,
                                int lastFullLength) {
}
