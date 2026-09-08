package com.lucky.agent.memory.config;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.memory.api.MemorySummaryModel;
import com.lucky.agent.memory.md.HierarchyMemoryRetriever;
import com.lucky.agent.memory.md.MarkdownMemoryWriter;
import com.lucky.agent.memory.pipeline.Cleaner;
import com.lucky.agent.memory.pipeline.DecayManager;
import com.lucky.agent.memory.pipeline.FactExtractor;
import com.lucky.agent.memory.pipeline.NoiseFilter;
import com.lucky.agent.memory.retract.Compressor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-memory 配置：记忆管线组件装配。
 */
@Configuration
@EnableConfigurationProperties({MemoryProperties.class, MemoryMdProperties.class})
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

    /**
     * 分层 Markdown 记忆写入器：依赖上层提供的 {@link MemorySummaryModel}（记忆管理 Agent）。
     * 未接入时（agent-core 不在 classpath）用截断降级实现，保证链路可用。
     */
    @Bean
    public MarkdownMemoryWriter markdownMemoryWriter(WorkspaceDirs dirs, MemoryMdProperties props,
                                                     ObjectProvider<MemorySummaryModel> summaryModelProvider) {
        MemorySummaryModel model = summaryModelProvider.getIfAvailable(
                () -> (instruction, content) -> truncateFallback(content));
        return new MarkdownMemoryWriter(dirs, props, model);
    }

    /** 分层召回读取器（按层读 md）。 */
    @Bean
    public HierarchyMemoryRetriever hierarchyMemoryRetriever(MarkdownMemoryWriter writer, MemoryMdProperties props) {
        return new HierarchyMemoryRetriever(writer, props);
    }

    /** 未配置记忆管理 Agent 时的降级总结：不调 LLM，直接截断返回（避免链路因缺模型失败）。 */
    private static String truncateFallback(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() > 2000) {
            return content.substring(0, 2000) + "\n…（未配置记忆管理 Agent，未做 LLM 总结）";
        }
        return content;
    }
}
