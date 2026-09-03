package com.lucky.agent.model.config;

import com.lucky.agent.model.prompt.PromptCacheService;
import com.lucky.agent.model.prompt.SystemPromptAssembler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent-model 配置：提示词组装器与缓存服务装配。
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
@EnableScheduling
public class ModelModuleConfig {

    /** 提示词静态/动态分界组装器。 */
    @Bean
    public SystemPromptAssembler systemPromptAssembler() {
        return new SystemPromptAssembler();
    }

    /** 提示词缓存服务（含命中统计，供状态面板查询）。 */
    @Bean
    public PromptCacheService promptCacheService() {
        return new PromptCacheService();
    }
}
