package com.lucky.agent.skill.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.skill.SkillToolSource;
import com.lucky.agent.skill.api.SkillMatcher;
import com.lucky.agent.skill.api.SkillRegistry;
import com.lucky.agent.skill.match.SemanticMatcher;
import com.lucky.agent.skill.registry.LocalSkillRegistry;
import com.lucky.agent.skill.registry.SkillLoader;
import com.lucky.agent.skill.resolve.DependencyResolver;
import com.lucky.agent.skill.sandbox.SkillSandbox;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent-skill 配置：注册中心 + 语义匹配 + 依赖解析 + 执行沙箱 + 工具来源装配。
 */
@Configuration
@EnableConfigurationProperties(SkillProperties.class)
@EnableScheduling
public class SkillModuleConfig {

    /** 磁盘加载器（平台预置 + 用户自定义）。 */
    @Bean
    public SkillLoader skillLoader(WorkspaceDirs dirs, SkillProperties properties, ObjectMapper objectMapper) {
        return new SkillLoader(dirs, properties, objectMapper);
    }

    /** 本地注册中心（启动即加载，含热插拔定时扫描）。 */
    @Bean
    public LocalSkillRegistry skillRegistry(SkillLoader loader, WorkspaceDirs dirs, ObjectMapper objectMapper,
                                            SkillProperties properties, DependencyResolver dependencyResolver) {
        LocalSkillRegistry registry = new LocalSkillRegistry(loader, dirs, objectMapper, properties, dependencyResolver);
        registry.init();
        return registry;
    }

    /** 语义 Top-K 匹配（默认本机关键词 + 中文 bigram）。 */
    @Bean
    public SkillMatcher skillMatcher() {
        return new SemanticMatcher();
    }

    /** 依赖解析。 */
    @Bean
    public DependencyResolver dependencyResolver() {
        return new DependencyResolver();
    }

    /** 执行沙箱（经执行臂硬边界）。 */
    @Bean
    public SkillSandbox skillSandbox(FileService fileService, SkillLoader loader) {
        return new SkillSandbox(fileService, loader);
    }

    /** Skill 工具来源（注入 ToolGateway）。 */
    @Bean
    public ToolSource skillToolSource(SkillRegistry registry, SkillMatcher matcher, SkillSandbox sandbox,
                                      DependencyResolver resolver, SkillProperties properties) {
        return new SkillToolSource(registry, matcher, sandbox, resolver, properties);
    }
}
