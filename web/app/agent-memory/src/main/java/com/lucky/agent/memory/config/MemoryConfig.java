package com.lucky.agent.memory.config;

import com.lucky.agent.memory.pipeline.Cleaner;
import com.lucky.agent.memory.pipeline.DecayManager;
import com.lucky.agent.memory.pipeline.FactExtractor;
import com.lucky.agent.memory.pipeline.NoiseFilter;
import com.lucky.agent.memory.retract.Compressor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-memory 配置：记忆管线组件装配。
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    @Bean
    public NoiseFilter noiseFilter() {
        return new NoiseFilter();
    }

    @Bean
    public Cleaner cleaner() {
        return new Cleaner();
    }

    @Bean
    public FactExtractor factExtractor() {
        return new FactExtractor();
    }

    @Bean
    public DecayManager decayManager(MemoryProperties properties) {
        return new DecayManager(properties.decayPerDay());
    }

    @Bean
    public Compressor memoryCompressor() {
        return new Compressor();
    }
}
