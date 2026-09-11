package com.lucky.agent.memory.config;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.memory.service.MemorySummaryModel;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import com.lucky.agent.memory.support.md.MemoryIndex;
import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import com.lucky.agent.memory.support.pipeline.Cleaner;
import com.lucky.agent.memory.support.pipeline.DecayManager;
import com.lucky.agent.memory.support.pipeline.FactExtractor;
import com.lucky.agent.memory.support.pipeline.NoiseFilter;
import com.lucky.agent.memory.support.pipeline.Compressor;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent-memory 配置：记忆管线组件装配 + 做梦清理定时任务。
 */
@Configuration
@EnableScheduling
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

    /** 工作空间物理路径 → 记忆目录命名（全路径转义 + 存量迁移）。 */
    @Bean
    public WorkspaceMemoryPaths workspaceMemoryPaths(WorkspaceConfig workspaceConfig) {
        return new WorkspaceMemoryPaths(workspaceConfig);
    }

    /** 两级记忆索引（MEMORY.md，锚点 + 索引生成，上限可配）。 */
    @Bean
    public MemoryIndex memoryIndex(MemoryMdProperties props) {
        return new MemoryIndex(props.indexMaxLines(), props.indexMaxBytes());
    }

    /**
     * 分层 Markdown 记忆写入器：依赖上层提供的 {@link MemorySummaryModel}（记忆管理 Agent）。
     * 未接入时（agent-core 不在 classpath）用截断降级实现，保证链路可用。
     */
    @Bean
    public MarkdownMemoryWriter markdownMemoryWriter(WorkspaceDirs dirs, MemoryMdProperties props,
                                                     ObjectProvider<MemorySummaryModel> summaryModelProvider,
                                                     WorkspaceMemoryPaths workspaceMemoryPaths,
                                                     MemoryIndex memoryIndex) {
        MemorySummaryModel model = summaryModelProvider.getIfAvailable(
                () -> (instruction, content) -> truncateFallback(content));
        return new MarkdownMemoryWriter(dirs, props, model, workspaceMemoryPaths, memoryIndex);
    }

    /** 分层召回读取器（索引 + 按需条目 + 全量兜底）。 */
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
