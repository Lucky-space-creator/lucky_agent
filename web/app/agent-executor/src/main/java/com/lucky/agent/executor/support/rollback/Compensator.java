package com.lucky.agent.executor.support.rollback;

import com.lucky.agent.common.constant.WorkspaceDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 补偿器：按 checkpointId 还原本机快照（不依赖消息队列）。
 */
public class Compensator {

    private final WorkspaceDirs dirs;

    public Compensator(WorkspaceDirs dirs) {
        this.dirs = dirs;
    }

    /**
     * 还原本机快照。
     *
     * @param workspaceId   工作空间 ID
     * @param workspaceRoot 工作空间根目录
     * @param checkpointId  检查点 ID
     * @return true 还原成功
     */
    public boolean restore(String workspaceId, Path workspaceRoot, String checkpointId) {
        Path snapshotDir = dirs.rollbackDir().resolve(workspaceId).resolve(checkpointId);
        if (!Files.isDirectory(snapshotDir)) {
            return false;
        }
        try (var stream = Files.walk(snapshotDir)) {
            stream.filter(Files::isRegularFile).forEach(snapshotFile -> {
                Path rel = snapshotDir.relativize(snapshotFile);
                Path target = workspaceRoot.resolve(rel);
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(snapshotFile, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException("还原文件失败：" + target, e);
                }
            });
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("扫描快照目录失败：" + snapshotDir, e);
        }
    }
}
