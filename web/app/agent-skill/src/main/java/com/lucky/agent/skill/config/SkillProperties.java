package com.lucky.agent.skill.config;

import com.lucky.agent.common.constant.WorkspaceDirs;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-skill 配置项。
 *
 * @param topK       语义召回 Top-K（默认 5，非全量注入省 token）
 * @param userDir    用户自定义 Skill 目录（相对单根，默认 {@code skills}）
 * @param platformDir 平台预置目录（相对单根，默认 {@code platform}，只读）
 * @param hotReload   热插拔：定时扫描新增/删除 Skill（默认 true）
 * @param hotReloadMs 热插拔扫描周期（<b>毫秒</b>，默认 30000）
 */
@ConfigurationProperties(prefix = "skill")
public record SkillProperties(
        int topK,
        String userDir,
        String platformDir,
        boolean hotReload,
        long hotReloadMs) {

    public SkillProperties {
        if (topK <= 0) {
            topK = 5;
        }
        if (userDir == null || userDir.isBlank()) {
            userDir = WorkspaceDirs.SKILLS;
        }
        if (platformDir == null || platformDir.isBlank()) {
            platformDir = WorkspaceDirs.PLATFORM;
        }
        if (hotReloadMs <= 0) {
            hotReloadMs = 30000;
        }
    }
}
