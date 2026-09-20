package com.lucky.agent.memory.repository;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 平台轨记忆存储（{@code <frameworkRoot>/.platform/} 随包只读）。
 * <p>仅从发行包加载，写入即拒绝（ReadOnly），保证平台规范建议不被用户数据污染。</p>
 */

@Slf4j
@Service
public class PlatformMemoryStore implements MemoryStore {

    
    private static final String FILE_NAME = "platform_mem.jsonl";

    private final LocalFileBackend backend;
    private final Path platformFile;
    private volatile List<MemoryEntry> entries = new ArrayList<>();

    public PlatformMemoryStore(WorkspaceDirs dirs) {
        this.backend = new LocalFileBackend();
        this.platformFile = dirs.platformDir().resolve(FILE_NAME);
        load();
    }

    private void load() {
        if (!Files.exists(platformFile)) {
            return;
        }
        this.entries = backend.read(platformFile, MemoryEntry.class);
        log.info("加载平台记忆：共 {} 条", entries.size());
    }

    @Override
    public void append(MemoryEntry entry) {
        throw new AgentException("PLATFORM_READONLY", "平台记忆只读，禁止写入（隐私红线）");
    }

    @Override
    public List<MemoryEntry> loadAll(String userId) {
        return entries;
    }

    @Override
    public void clear(String userId) {
        // 平台记忆只读，不可清除
        log.debug("平台记忆只读，忽略清除请求");
    }
}
