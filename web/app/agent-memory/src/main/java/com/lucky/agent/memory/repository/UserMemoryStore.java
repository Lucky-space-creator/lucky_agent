package com.lucky.agent.memory.repository;

import com.lucky.agent.common.concurrent.ReadWriteLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import com.lucky.agent.memory.support.pipeline.Cleaner;
import com.lucky.agent.memory.support.pipeline.FactExtractor;
import com.lucky.agent.memory.support.pipeline.NoiseFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户轨记忆存储（JSONL，按「用户 + 工作空间」分组落盘）。
 * <p>目录结构（位于 {@code <frameworkRoot>/memory/<userId>/…/mem.jsonl}）：</p>
 * <pre>
 * memory/&lt;userId&gt;/mem.jsonl                        # 旧版全局轨（项目分组前，向后兼容读取）
 * memory/&lt;userId&gt;/&#123;&lt;工作空间全路径转义&gt;&#125;_ws/mem.jsonl  # 项目轨（按全路径命名，防跨项目串扰）
 * </pre>
 * <p>写入经 清洗（脱敏）→ 噪声过滤 → 事实萃取（置信度）→ 同项目去重（P1-9）；
 * 跨会话必选落盘，进程重启自动恢复（R7）。{@link #sweep} 供定时「做梦」清理调用：
 * 低置信衰减遗忘 + 容量上限裁剪，防止记忆无界增长。</p>
 */

@Slf4j
@Service
public class UserMemoryStore implements MemoryStore {

    private static final String FILE_NAME = "mem.jsonl";

    private final Path memoryDir;
    private final ReadWriteLockGuard lockGuard;
    private final NoiseFilter noiseFilter;
    private final FactExtractor factExtractor;
    private final Cleaner cleaner;
    private final WorkspaceMemoryPaths paths;
    private final LocalFileBackend backend;
    private final ConcurrentMap<String, List<MemoryEntry>> cache = new ConcurrentHashMap<>();

    public UserMemoryStore(WorkspaceDirs dirs, ReadWriteLockGuard memoryLockGuard,
                           NoiseFilter noiseFilter, FactExtractor factExtractor, Cleaner cleaner,
                           WorkspaceMemoryPaths paths) {
        this.memoryDir = dirs.memoryDir();
        this.lockGuard = memoryLockGuard;
        this.noiseFilter = noiseFilter;
        this.factExtractor = factExtractor;
        this.cleaner = cleaner;
        this.paths = paths;
        this.backend = new LocalFileBackend();
    }

    @Override
    public void append(MemoryEntry entry) {
        if (entry == null) {
            return;
        }
        // 清洗（脱敏敏感串、折叠空白）后再走噪声过滤
        String cleaned = cleaner.clean(entry.content() == null ? "" : entry.content());
        if (noiseFilter.isNoise(cleaned)) {
            return;
        }
        entry = new MemoryEntry(entry.ts(), entry.track(), entry.userId(), entry.workspaceId(),
                cleaned, entry.confidence(), entry.source());
        if (entry.confidence() <= 0) {
            entry = new MemoryEntry(entry.ts(), entry.track(), entry.userId(), entry.workspaceId(),
                    cleaned, factExtractor.assignConfidence(cleaned), entry.source());
        }
        lockGuard.lockWrite();
        try {
            Path file = userFile(entry.userId(), entry.workspaceId());
            // 同项目去重：内容已存在且来源一致则不重复写入（防每会话重复沉淀同一事实）
            if (containsSame(loadScope(entry.userId(), entry.workspaceId()), entry)) {
                return;
            }
            backend.append(file, entry);
            cache.computeIfAbsent(entry.userId(), k -> new ArrayList<>()).add(entry);
        } finally {
            lockGuard.unlockWrite();
        }
    }

    @Override
    public List<MemoryEntry> loadAll(String userId) {
        return cache.computeIfAbsent(userId, this::loadFromDir);
    }

    @Override
    public List<MemoryEntry> loadAll(String userId, String workspaceId) {
        return loadScope(userId, workspaceId);
    }

    @Override
    public void clear(String userId) {
        lockGuard.lockWrite();
        try {
            Path dir = userDir(userId);
            deleteRecursively(dir);
            cache.remove(userId);
            log.info("已清除用户记忆：{}", userId);
        } finally {
            lockGuard.unlockWrite();
        }
    }

    /**
     * 做梦清理：对每个（用户 × 工作空间）记忆文件施加衰减 → 遗忘阈值裁剪 → 容量上限，
     * 只保留最新且置信度仍达标的条目，防止记忆文件无界增长。
     *
     * @param decayPerDay    每日衰减系数（0~1）
     * @param forgetThreshold 遗忘阈值（低于则删除）
     * @param maxPerScope    单项目容量上限（超出按时间保留最新）
     */
    public void sweep(double decayPerDay, double forgetThreshold, int maxPerScope) {
        lockGuard.lockWrite();
        try {
            if (!Files.isDirectory(memoryDir)) {
                return;
            }
            try (Stream<Path> users = Files.list(memoryDir)) {
                users.filter(Files::isDirectory).forEach(userDir -> {
                    try (Stream<Path> files = Files.walk(userDir)) {
                        files.filter(p -> p.getFileName().toString().equals(FILE_NAME)
                                        && Files.isRegularFile(p))
                                .forEach(file -> sweepFile(file, decayPerDay, forgetThreshold, maxPerScope));
                    } catch (IOException e) {
                        log.warn("做梦清理遍历失败：{}", userDir, e);
                    }
                });
            } catch (IOException e) {
                log.warn("做梦清理失败：{}", memoryDir, e);
            }
        } finally {
            lockGuard.unlockWrite();
        }
    }

    /** 对单个 mem.jsonl 施加衰减/遗忘/上限后整文件重写。 */
    private void sweepFile(Path file, double decayPerDay, double forgetThreshold, int maxPerScope) {
        try {
            List<MemoryEntry> entries = backend.read(file, MemoryEntry.class);
            if (entries.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            List<MemoryEntry> kept = entries.stream()
                    .map(e -> new MemoryEntry(e.ts(), e.track(), e.userId(), e.workspaceId(),
                            e.content(), decayed(e.confidence(), ageDays(e.ts(), now), decayPerDay), e.source()))
                    .filter(e -> e.confidence() >= forgetThreshold)
                    .sorted(Comparator.comparing(MemoryEntry::ts,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(maxPerScope)
                    .toList();
            if (kept.size() != entries.size()) {
                backend.rewrite(file, kept);
                log.info("做梦清理：{} → {} 条（衰减/遗忘/上限）", file, kept.size());
            }
        } catch (Exception e) {
            log.warn("做梦清理单文件失败：{}", file, e);
        }
    }

    private boolean containsSame(List<MemoryEntry> scope, MemoryEntry entry) {
        if (scope == null || entry.content() == null) {
            return false;
        }
        String content = entry.content().trim();
        return scope.stream().anyMatch(e -> content.equals(e.content().trim())
                && (entry.source() == null ? e.source() == null : entry.source().equals(e.source())));
    }

    /** 加载某个作用域（工作空间轨 + 旧版全局轨兼容）。 */
    private List<MemoryEntry> loadScope(String userId, String workspaceId) {
        try {
            lockGuard.lockRead();
            List<MemoryEntry> result = new ArrayList<>();
            Path scoped = userFile(userId, workspaceId);
            if (Files.exists(scoped)) {
                result.addAll(backend.read(scoped, MemoryEntry.class));
            }
            // 旧版全局轨（项目分组前写入根目录 mem.jsonl）合并读取，保证存量数据不丢
            Path legacy = userDir(userId).resolve(FILE_NAME);
            if (!legacy.equals(scoped) && Files.exists(legacy)) {
                result.addAll(backend.read(legacy, MemoryEntry.class));
            }
            return result;
        } finally {
            lockGuard.unlockRead();
        }
    }

    private List<MemoryEntry> loadFromDir(String userId) {
        Path dir = userDir(userId);
        List<MemoryEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.getFileName().toString().equals(FILE_NAME)
                            && Files.isRegularFile(p))
                    .forEach(p -> entries.addAll(backend.read(p, MemoryEntry.class)));
        } catch (IOException e) {
            log.warn("加载用户记忆失败：{}", userId, e);
        }
        return entries;
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除记忆文件失败：{}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("删除用户记忆目录失败：{}", dir, e);
        }
    }

    private Path userDir(String userId) {
        return memoryDir.resolve(userId == null ? "default" : userId);
    }

    private Path userFile(String userId, String workspaceId) {
        return paths.suffixedDirOf(userDir(userId), workspaceId, "_ws").resolve(FILE_NAME);
    }

    /** 记忆条目存续天数（ts 解析失败视为 0 天，不衰减）。 */
    private long ageDays(String ts, long nowMillis) {
        try {
            if (ts == null || ts.isBlank()) {
                return 0;
            }
            Instant parsed = Instant.parse(ts);
            return ChronoUnit.DAYS.between(parsed, Instant.ofEpochMilli(nowMillis));
        } catch (Exception e) {
            return 0;
        }
    }

    private double decayed(double confidence, long ageDays, double decayPerDay) {
        return confidence * Math.pow(decayPerDay, Math.max(0, ageDays));
    }
}