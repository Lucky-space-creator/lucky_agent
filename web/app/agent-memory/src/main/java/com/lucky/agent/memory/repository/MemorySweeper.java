package com.lucky.agent.memory.repository;

import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.config.MemoryProperties;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 记忆「做梦」调度器（对标 Claude Auto Memory Dream）。
 *
 * <ul>
 *   <li>{@link #sweep()}：JSONL 事实带衰减/遗忘/容量裁剪（每周期，默认 6h）；</li>
 *   <li>{@link #dream()}：md 分层记忆<b>合成</b>：定向（扫描新增会话 md）→ 收集 →
 *       合并（LLM 分类去重、矛盾以时间近者优先）→ 修剪（索引上限兜底）。
 *       门控：周期（默认 24h）+ 累计新增会话数（默认 5）+ 单实例锁；合成与收尾总结
 *       共用 writer 同步锁，互斥写盘，失败保留旧文件。</li>
 * </ul>
 */
@Slf4j
@Component
public class MemorySweeper {

    /** 单项目记忆条目上限（JSONL 超出按时间保留最新，防止无界增长）。 */
    private static final int MAX_ENTRIES_PER_SCOPE = 200;

    /** 上次 Dream 合成时间戳文件（md root 下）。 */
    private static final String DREAM_STAMP = ".dream-stamp";

    private final UserMemoryStore userStore;
    private final MemoryProperties properties;
    private final MarkdownMemoryWriter mdWriter;
    private final MemoryMdProperties mdProps;
    private final AtomicBoolean dreaming = new AtomicBoolean(false);

    public MemorySweeper(UserMemoryStore userStore, MemoryProperties properties,
                         MarkdownMemoryWriter mdWriter, MemoryMdProperties mdProps) {
        this.userStore = userStore;
        this.properties = properties;
        this.mdWriter = mdWriter;
        this.mdProps = mdProps;
    }

    /** JSONL 做梦清理：衰减 + 遗忘 + 容量上限（失败不阻断，仅告警）。启动延迟避免启动即清理。 */
    @Scheduled(fixedDelayString = "${memory.sweep-interval-ms:21600000}",
            initialDelayString = "${memory.sweep-initial-delay-ms:60000}")
    public void sweep() {
        try {
            userStore.sweep(properties.decayPerDay(), properties.forgetThreshold(), MAX_ENTRIES_PER_SCOPE);
            log.info("记忆做梦清理完成：decayPerDay={} forgetThreshold={}",
                    properties.decayPerDay(), properties.forgetThreshold());
        } catch (Exception e) {
            log.warn("记忆做梦清理异常：{}", e.getMessage());
        }
    }

    /**
     * Dream 合成：新增会话数达到阈值才合并，避免频繁 LLM 调用；与收尾总结互斥写盘。
     * 启动延迟 10 分钟，避免应用启动即触发合成。
     */
    @Scheduled(fixedDelayString = "${memory.md.dream-interval-ms:86400000}",
            initialDelayString = "${memory.md.dream-initial-delay-ms:600000}")
    public void dream() {
        if (!mdProps.enabled()) {
            return;
        }
        if (!dreaming.compareAndSet(false, true)) {
            log.debug("Dream 已在进行，跳过本次触发");
            return;
        }
        try {
            Path root = mdWriter.root();
            long since = readStamp(root);
            Map<Path, List<Path>> newSessionFiles = scanNewSessions(root, since);
            int total = newSessionFiles.values().stream().mapToInt(List::size).sum();
            if (total < mdProps.dreamMinNewSessions()) {
                log.debug("Dream 跳过：新增会话 {} < {}（min）", total, mdProps.dreamMinNewSessions());
                return;
            }
            // 收集：按工作空间目录聚合新增会话总结 → 合并（项目→整体）
            for (Map.Entry<Path, List<Path>> e : newSessionFiles.entrySet()) {
                StringBuilder material = new StringBuilder();
                for (Path session : e.getValue()) {
                    material.append(readFile(session)).append("\n\n");
                }
                mdWriter.consolidateDir(e.getKey(), material.toString());
            }
            writeStamp(root, System.currentTimeMillis());
            log.info("Dream 合成完成：workspaces={} 新增会话={}", newSessionFiles.size(), total);
        } catch (Exception e) {
            log.warn("Dream 合成异常（保留原记忆）：{}", e.getMessage());
        } finally {
            dreaming.set(false);
        }
    }

    /** 定向：扫描 {@code root/<workspace>/sessions/*.md} 中 mtime &gt; since 的文件，按工作空间目录聚合。 */
    private Map<Path, List<Path>> scanNewSessions(Path root, long since) {
        Map<Path, List<Path>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getNameCount() >= 3)
                    .filter(p -> p.getName(p.getNameCount() - 2).toString().equals("sessions"))
                    .filter(p -> isNew(p, since))
                    .forEach(p -> {
                        Path workspaceDir = p.getParent() == null ? null : p.getParent().getParent();
                        if (workspaceDir != null && !workspaceDir.equals(root)) {
                            result.computeIfAbsent(workspaceDir, k -> new ArrayList<>()).add(p);
                        }
                    });
        } catch (IOException e) {
            log.warn("Dream 扫描会话目录失败：{}", root, e);
        }
        return result;
    }

    private boolean isNew(Path sessionFile, long since) {
        try {
            return Files.getLastModifiedTime(sessionFile).toMillis() > since;
        } catch (IOException e) {
            return false;
        }
    }

    private long readStamp(Path root) {
        try {
            Path stamp = root.resolve(DREAM_STAMP);
            return Files.exists(stamp)
                    ? Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).trim())
                    : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void writeStamp(Path root, long millis) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(DREAM_STAMP), String.valueOf(millis), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入 Dream 时间戳失败：{}", root, e);
        }
    }

    private String readFile(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取 Dream 素材失败：{}", file, e);
            return "";
        }
    }
}