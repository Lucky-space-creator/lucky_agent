package com.lucky.agent.executor.support.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 补偿器：按 checkpointId 还原本机快照（不依赖消息队列）。
 *
 * <p><b>台账 #12</b>：旧实现只有一个动作 —— 把快照目录里的文件无条件覆盖回工作区。
 * 这带来三个后果，全部会让用户「以为回滚了，其实没有」：</p>
 * <ol>
 *   <li><b>新建文件残留</b>：快照只存「操作前已存在」的文件，WRITE 新建的文件不在快照里，
 *       还原后照样留在工作区。MKDIR 新建的目录同理。</li>
 *   <li><b>RENAME 目标残留</b>：只快照了源路径，还原后源文件回来了、目标文件也还在，
 *       一次重命名变成两个文件。</li>
 *   <li><b>覆盖用户改动</b>：无条件 {@code REPLACE_EXISTING} 会把「快照之后用户自己改过」
 *       的内容直接盖掉，且不留副本。</li>
 * </ol>
 *
 * <p>现在改为按 {@code __manifest__.json} 精确还原：{@code existed=false} 的路径删除，
 * {@code existed=true} 的先比对当前内容，与快照不同则备份到
 * {@code <checkpointId>/__pre-restore__/} 再覆盖。旧快照没有 manifest，回落旧行为。</p>
 */
@Slf4j
public class Compensator {

    /** 还原前备份「用户改动」的子目录名（不参与还原，避免被再次当成快照文件）。 */
    public static final String PRE_RESTORE_DIR = "__pre-restore__";

    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;

    public Compensator(WorkspaceDirs dirs) {
        this(dirs, new ObjectMapper());
    }

    public Compensator(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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
        Path root = workspaceRoot.toAbsolutePath().normalize();
        SnapshotManifest manifest = readManifest(snapshotDir);
        if (manifest == null || manifest.entries().isEmpty()) {
            // 旧快照（无 manifest）：维持原有「全量覆盖」语义，避免历史快照失效
            return restoreLegacy(snapshotDir, root);
        }
        try {
            for (SnapshotManifest.Entry entry : manifest.entries()) {
                Path target = root.resolve(entry.path()).normalize();
                // 防御：清单被篡改时也不允许写出工作空间
                if (!target.startsWith(root)) {
                    log.warn("还原跳过越界路径：checkpoint={} path={}", checkpointId, entry.path());
                    continue;
                }
                if (!entry.existed()) {
                    removeCreated(target);
                    continue;
                }
                Path snapshotFile = snapshotDir.resolve(entry.path()).normalize();
                if (!Files.isRegularFile(snapshotFile)) {
                    continue;
                }
                backupIfUserModified(snapshotDir, entry.path(), target);
                Files.createDirectories(target.getParent());
                Files.copy(snapshotFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("还原快照失败：" + snapshotDir, e);
        }
    }

    /** 旧快照回落：遍历快照目录全量覆盖（跳过 manifest 与备份目录）。 */
    private boolean restoreLegacy(Path snapshotDir, Path root) {
        try (Stream<Path> stream = Files.walk(snapshotDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isBookkeeping(snapshotDir.relativize(p)))
                    .forEach(snapshotFile -> {
                        Path rel = snapshotDir.relativize(snapshotFile);
                        Path target = root.resolve(rel).normalize();
                        if (!target.startsWith(root)) {
                            return;
                        }
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

    /**
     * 删除「本次操作新建」的路径。
     *
     * <p>目录只在<b>空</b>时删除：非空说明快照之后有人往里写了东西，删掉等于替用户丢数据。
     * 这类残留只记日志，不阻断整体还原。</p>
     */
    private void removeCreated(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
            log.info("回滚删除操作新建的路径：{}", target);
        } catch (DirectoryNotEmptyException e) {
            log.warn("回滚跳过非空目录（快照后已写入内容，不代为删除）：{}", target);
        } catch (IOException e) {
            log.warn("回滚删除新建路径失败：{}", target, e);
        }
    }

    /**
     * 覆盖前保护：若目标当前内容与快照不同（说明快照后被改过），先把当前内容另存一份。
     * 这样回滚不会静默丢掉用户在中间做的工作。
     */
    private void backupIfUserModified(Path snapshotDir, String rel, Path target) {
        if (!Files.isRegularFile(target)) {
            return;
        }
        Path snapshotFile = snapshotDir.resolve(rel).normalize();
        try {
            if (Files.mismatch(snapshotFile, target) == -1L) {
                return; // 内容一致，无需备份
            }
            Path backup = snapshotDir.resolve(PRE_RESTORE_DIR).resolve(rel).normalize();
            if (!backup.startsWith(snapshotDir)) {
                return;
            }
            Files.createDirectories(backup.getParent());
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("回滚前备份用户改动：{} -> {}", target, backup);
        } catch (IOException e) {
            log.warn("回滚前备份失败（继续覆盖，先保证回滚可用）：{}", target, e);
        }
    }

    /** manifest 与备份目录都不属于快照内容，遍历时必须跳过。 */
    private static boolean isBookkeeping(Path rel) {
        for (Path part : rel) {
            String name = part.toString();
            if (name.equals(CheckpointSnapshoter.MANIFEST_NAME) || name.equals(PRE_RESTORE_DIR)) {
                return true;
            }
        }
        return false;
    }

    private SnapshotManifest readManifest(Path snapshotDir) {
        Path file = snapshotDir.resolve(CheckpointSnapshoter.MANIFEST_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), SnapshotManifest.class);
        } catch (IOException e) {
            log.warn("读取检查点清单失败，回落全量覆盖：{}", file, e);
            return null;
        }
    }
}
