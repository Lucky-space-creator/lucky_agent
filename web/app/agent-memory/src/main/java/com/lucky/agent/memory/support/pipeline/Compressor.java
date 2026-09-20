package com.lucky.agent.memory.support.pipeline;

import com.lucky.agent.memory.api.dto.MemoryEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 长记忆下沉（配合上下文压缩，Phase 2 完整实现）。
 *
 * <p>MVP 仅做单条长度截断：保留关键前缀；摘要/向量下沉在 Phase 2 补齐。</p>
 */
public class Compressor {

    private static final int MAX_ENTRY_LENGTH = 500;

    /**
     * 压缩记忆条目列表。
     *
     * @param entries 原始条目
     * @return 压缩后条目
     */
    public List<MemoryEntry> compress(List<MemoryEntry> entries) {
        List<MemoryEntry> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        for (MemoryEntry entry : entries) {
            String content = entry.content();
            if (content != null && content.length() > MAX_ENTRY_LENGTH) {
                content = content.substring(0, MAX_ENTRY_LENGTH) + "…（已压缩）";
            }
            result.add(new MemoryEntry(entry.ts(), entry.track(), entry.userId(), entry.workspaceId(),
                    content, entry.confidence(), entry.source()));
        }
        return result;
    }
}
