package com.lucky.agent.executor.support.rollback;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.api.dto.Snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 检查点快照：关键操作前在本机生成快照（按 workspaceId 分桶存 {@code .rollback/}）。
 */
public class CheckpointSnapshoter {

    private final WorkspaceDirs dirs;
    private final SnapshotIndex snapshotIndex;

    public CheckpointSnapshoter(WorkspaceDirs dirs, SnapshotIndex snapshotIndex) {
        this.dirs = dirs;
        this.snapshotIndex = snapshotIndex;
    }

    /**
     * 为工作空间内受影响文件创建检查点快照。
     *
     * @param workspaceId     工作空间 ID
     * @param workspaceRoot   工作空间根目录
     * @param affectedFiles   受影响文件（相对路径）
     * @return 快照记录
     */
    public Snapshot checkpoint(String workspaceId, Path workspaceRoot, List<String> affectedFiles) {
        String checkpointId = UUID.randomUUID().toString();
        Path snapshotDir = dirs.rollbackDir().resolve(workspaceId).resolve(checkpointId);
        try {
            Files.createDirectories(snapshotDir);
            for (String rel : affectedFiles) {
                Path source = workspaceRoot.resolve(rel);
                if (Files.exists(source)) {
                    Path target = snapshotDir.resolve(rel);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建检查点快照失败：" + workspaceId, e);
        }
        List<Snapshot> existing = snapshotIndex.read(dirs.rollbackDir(), workspaceId);
        String parentId = existing.isEmpty() ? null : existing.get(existing.size() - 1).checkpointId();
        Snapshot snapshot = Snapshot.of(checkpointId, workspaceId, Instant.now(), parentId,
                List.copyOf(affectedFiles));
        snapshotIndex.append(dirs.rollbackDir(), snapshot);
        return snapshot;
    }
}
