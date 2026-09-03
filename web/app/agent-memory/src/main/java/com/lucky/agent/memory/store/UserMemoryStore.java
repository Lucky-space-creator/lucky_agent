package com.lucky.agent.memory.store;

import com.lucky.agent.common.concurrent.ReadWriteLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.pipeline.FactExtractor;
import com.lucky.agent.memory.pipeline.NoiseFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户轨记忆存储（{@code <frameworkRoot>/.memory/<userId>/mem.jsonl} + 内存缓存）。
 * <p>写入经噪声过滤 → 事实萃取（置信度）；跨会话必选落盘，进程重启自动恢复（R7）。
 * 并发写由 common 读写锁保护，杜绝 JSONL 行交错。</p>
 */

@Slf4j
@Service
public class UserMemoryStore implements MemoryStore {

    
    private static final String FILE_NAME = "mem.jsonl";

    private final Path memoryDir;
    private final ReadWriteLockGuard lockGuard;
    private final NoiseFilter noiseFilter;
    private final FactExtractor factExtractor;
    private final LocalFileBackend backend;
    private final ConcurrentMap<String, List<MemoryEntry>> cache = new ConcurrentHashMap<>();

    public UserMemoryStore(WorkspaceDirs dirs, ReadWriteLockGuard memoryLockGuard,
                           NoiseFilter noiseFilter, FactExtractor factExtractor) {
        this.memoryDir = dirs.memoryDir();
        this.lockGuard = memoryLockGuard;
        this.noiseFilter = noiseFilter;
        this.factExtractor = factExtractor;
        this.backend = new LocalFileBackend();
    }

    @Override
    public void append(MemoryEntry entry) {
        if (entry == null || entry.content() == null || noiseFilter.isNoise(entry.content())) {
            return;
        }
        if (entry.confidence() <= 0) {
            entry = new MemoryEntry(entry.ts(), entry.track(), entry.userId(), entry.content(),
                    factExtractor.assignConfidence(entry.content()), entry.source());
        }
        lockGuard.lockWrite();
        try {
            Path file = userFile(entry.userId());
            backend.append(file, entry);
            cache.computeIfAbsent(entry.userId(), k -> new ArrayList<>()).add(entry);
        } finally {
            lockGuard.unlockWrite();
        }
    }

    @Override
    public List<MemoryEntry> loadAll(String userId) {
        return cache.computeIfAbsent(userId, this::loadFromFile);
    }

    @Override
    public void clear(String userId) {
        lockGuard.lockWrite();
        try {
            Path file = userFile(userId);
            Files.deleteIfExists(file);
            cache.remove(userId);
            log.info("已清除用户记忆：{}", userId);
        } catch (IOException e) {
            throw new AgentException("MEMORY_CLEAR_FAILED", "清除用户记忆失败：" + userId, e);
        } finally {
            lockGuard.unlockWrite();
        }
    }

    private List<MemoryEntry> loadFromFile(String userId) {
        Path file = userFile(userId);
        List<MemoryEntry> entries = backend.read(file, MemoryEntry.class);
        log.info("加载用户记忆：{}，共 {} 条", userId, entries.size());
        return entries;
    }

    private Path userFile(String userId) {
        return memoryDir.resolve(userId == null ? "default" : userId).resolve(FILE_NAME);
    }
}
