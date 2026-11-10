package com.lucky.agent.executor.support.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.api.dto.Snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 检查点快照：关键操作前在本机生成快照（按 workspaceId 分桶存 {@code .rollback/}）。
 *
 * <p><b>台账 #12</b>：快照目录只存「操作前已存在」的文件副本，因此必须额外落一份
 * {@code __manifest__.json} 记录每个受影响路径在操作前是否存在。否则还原时无法区分
 * 「新建」与「没快照到」，新建文件 / MKDIR 目录 / RENAME 目标都会残留，
 * 用户看到的是「回滚了但产物还在」。</p>
 */
public class CheckpointSnapshoter {

    /** 清单文件名（放在快照目录内，随快照一起清理；还原时按它精确回滚）。 */
    public static final String MANIFEST_NAME = "__manifest__.json";

    private final WorkspaceDirs dirs;
    private final SnapshotIndex snapshotIndex;
    private final ObjectMapper objectMapper;

    public CheckpointSnapshoter(WorkspaceDirs dirs, SnapshotIndex snapshotIndex) {
        this(dirs, snapshotIndex, new ObjectMapper());
    }

    public CheckpointSnapshoter(WorkspaceDirs dirs, SnapshotIndex snapshotIndex, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.snapshotIndex = snapshotIndex;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 为工作空间内受影响文件创建检查点快照。
     *
     * @param workspaceId   工作空间 ID
     * @param workspaceRoot 工作空间根目录
     * @param affectedFiles 受影响文件（相对路径）
     * @return 快照记录
     */
    public Snapshot checkpoint(String workspaceId, Path workspaceRoot, List<String> affectedFiles) {
        String checkpointId = UUID.randomUUID().toString();
        Path snapshotDir = dirs.rollbackDir().resolve(workspaceId).resolve(checkpointId);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> created = new ArrayList<>();
        List<SnapshotManifest.Entry> entries = new ArrayList<>();
        try {
            Files.createDirectories(snapshotDir);
            for (String raw : affectedFiles == null ? List.<String>of() : affectedFiles) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Path source = root.resolve(raw).normalize();
                // 越界（理论上已被 BoundaryGuard 拦下，这里只是不让快照本身成为新的逃逸口）
                if (!source.startsWith(root)) {
                    continue;
                }
                String rel = root.relativize(source).toString();
                boolean existed = Files.exists(source);
                entries.add(new SnapshotManifest.Entry(rel, existed));
                if (!existed) {
                    created.add(rel);
                    continue;
                }
                Path target = snapshotDir.resolve(rel).normalize();
                if (!target.startsWith(snapshotDir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(snapshotDir.resolve(MANIFEST_NAME),
                    objectMapper.writeValueAsBytes(SnapshotManifest.of(checkpointId, entries)));
        } catch (IOException e) {
            throw new IllegalStateException("创建检查点快照失败：" + workspaceId, e);
        }
        List<Snapshot> existing = snapshotIndex.read(dirs.rollbackDir(), workspaceId);
        String parentId = existing.isEmpty() ? null : existing.get(existing.size() - 1).checkpointId();
        Snapshot snapshot = Snapshot.of(checkpointId, workspaceId, Instant.now(), parentId,
                List.copyOf(entries.stream().map(SnapshotManifest.Entry::path).toList()),
                List.copyOf(created));
        snapshotIndex.append(dirs.rollbackDir(), snapshot);
        return snapshot;
    }
}
