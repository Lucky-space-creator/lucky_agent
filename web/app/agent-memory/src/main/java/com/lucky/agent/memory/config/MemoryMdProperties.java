package com.lucky.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分层 Markdown 记忆配置项（prefix = {@code memory.md}）。
 *
 * @param enabled              分层 md 记忆总开关（false 时回退旧 JSONL 双轨记忆）
 * @param root                 分层记忆根目录名（相对框架根 memory/ 下，默认 {@code md}）
 * @param triggerTurns         会话内触发总结的轮数阈值（仅作节流参考）
 * @param maxRetry             LLM 总结失败重试上限
 * @param indexMaxLines        MEMORY.md 索引行数上限
 * @param indexMaxBytes        MEMORY.md 索引字节上限
 * @param prefetchEnabled      记忆预取选择器开关（false 回退「索引全量注入」）
 * @param prefetchTopK         预取选择器每次最多挑出的条目数
 * @param prefetchModelId      可选：选择器专用廉价端点 id（空则回退主端点）
 * @param dreamIntervalHours   Dream 合成周期（小时）
 * @param dreamMinNewSessions  Dream 触发最少新增会话数
 */
@ConfigurationProperties(prefix = "memory.md")
public record MemoryMdProperties(
        boolean enabled,
        String root,
        int triggerTurns,
        int maxRetry,
        int indexMaxLines,
        int indexMaxBytes,
        boolean prefetchEnabled,
        int prefetchTopK,
        String prefetchModelId,
        int dreamIntervalHours,
        int dreamMinNewSessions) {

    public MemoryMdProperties {
        if (root == null || root.isBlank()) {
            root = "md";
        }
        if (triggerTurns <= 0) {
            triggerTurns = 5;
        }
        if (maxRetry <= 0) {
            maxRetry = 2;
        }
        if (indexMaxLines <= 0) {
            indexMaxLines = 200;
        }
        if (indexMaxBytes <= 0) {
            indexMaxBytes = 25600;
        }
        if (prefetchTopK <= 0) {
            prefetchTopK = 5;
        }
        if (prefetchModelId == null) {
            prefetchModelId = "";
        }
        if (dreamIntervalHours <= 0) {
            dreamIntervalHours = 24;
        }
        if (dreamMinNewSessions <= 0) {
            dreamMinNewSessions = 5;
        }
    }
}