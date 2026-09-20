package com.lucky.agent.memory.util;

import com.lucky.agent.workspace.api.WorkspaceConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 记忆磁盘目录命名（用户级在 memory/ 根下；本地级按工作空间<b>物理全路径</b>转义命名，
 * 对标 Claude Code {@code projects/<路径转义>/}，跨项目不串扰、目录可读可审计）。
 *
 * <p>转义规则：保留 Unicode 字母/数字/下划线/连字符，其余（含 {@code \ : . 空格}）替换为
 * {@code _}，并去掉首尾下划线。例：{@code C:\Users\LENOVO\新项目} → {@code C_Users_LENOVO_新项目}。</p>
 *
 * <p><b>存量迁移</b>：历史版本以 {@link #legacyName(String)}（sanitize(workspaceId)）命名目录，
 * 首次访问某工作空间时检测旧目录并整体 rename 到新全路径目录（原子优先），失败不阻断，
 * 读取侧仍双目录兜底，保证不丢存量记忆。</p>
 */
@Slf4j
public class WorkspaceMemoryPaths {

    /** 工作空间无法解析物理路径时回退的默认目录名。 */
    public static final String DEFAULT_DIR = "default";

    private final WorkspaceConfig workspaceConfig;

    public WorkspaceMemoryPaths(WorkspaceConfig workspaceConfig) {
        this.workspaceConfig = workspaceConfig;
    }

    /**
     * 工作空间记忆目录名：优先「物理全路径转义」；无法解析（未配置/异常）回退旧 sanitize 规则。
     *
     * @param workspaceId 工作空间 ID
     * @return 目录名（非空）
     */
    public String dirName(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return DEFAULT_DIR;
        }
        if (workspaceConfig != null) {
            try {
                Optional<String> path = workspaceConfig.physicalPathOf(workspaceId);
                if (path.isPresent() && !path.get().isBlank()) {
                    return escape(path.get());
                }
            } catch (Exception e) {
                log.warn("解析工作空间物理路径失败，回退旧命名：ws={} err={}", workspaceId, e.getMessage());
            }
        }
        return legacyName(workspaceId);
    }

    /**
     * 解析（必要时迁移）某个工作空间的本地级记忆目录。
     * <p>目标目录 = {@code parent}/{@link #dirName(workspaceId)}；若旧命名目录存在且新目录
     * 不存在，则整体 rename 迁移。目录为纯计算结果，不保证磁盘上存在（由调用方创建/读取）。</p>
     *
     * @param parent      上一级目录（md 轨为 md 根；JSONL 轨为 userId 目录）
     * @param workspaceId 工作空间 ID
     * @return 该工作空间的记忆目录
     */
    public Path dirOf(Path parent, String workspaceId) {
        return suffixedDirOf(parent, workspaceId, "");
    }

    /**
     * 解析（必要时迁移）带后缀的工作空间目录（JSONL 轨用 {@code _ws} 后缀与 md 轨区分）。
     *
     * @param parent      上一级目录
     * @param workspaceId 工作空间 ID
     * @param suffix      目录名后缀（可为空串）
     * @return 该工作空间的记忆目录
     */
    public Path suffixedDirOf(Path parent, String workspaceId, String suffix) {
        String sfx = suffix == null ? "" : suffix;
        Path fresh = parent.resolve(dirName(workspaceId) + sfx);
        Path legacy = parent.resolve(legacyName(workspaceId) + sfx);
        if (!fresh.equals(legacy) && Files.isDirectory(legacy) && !Files.exists(fresh)) {
            migrate(legacy, fresh);
        }
        return fresh;
    }

    /** 旧版目录命名（sanitize(workspaceId)，历史数据兼容）。 */
    public String legacyName(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return DEFAULT_DIR;
        }
        String sanitized = workspaceId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.isEmpty() ? DEFAULT_DIR : sanitized;
    }

    /** 路径转义（保留 Unicode 字母/数字/_/-，其余替换 _，去首尾 _）。 */
    public static String escape(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DIR;
        }
        String escaped = raw.replaceAll("[^\\p{L}\\p{N}_-]+", "_").replaceAll("^_+|_+$", "");
        return escaped.isEmpty() ? DEFAULT_DIR : escaped;
    }

    /** 原子优先的目录迁移（目标已存在视为已完成，忽略；失败仅告警不抛出）。 */
    private void migrate(Path legacy, Path fresh) {
        try {
            Files.move(legacy, fresh, StandardCopyOption.ATOMIC_MOVE);
            log.info("记忆目录迁移：{} -> {}", legacy, fresh);
        } catch (IOException atomicFailed) {
            try {
                Files.move(legacy, fresh);
                log.info("记忆目录迁移（非原子）：{} -> {}", legacy, fresh);
            } catch (IOException e) {
                log.warn("记忆目录迁移失败（读取侧双目录兜底）：{} err={}", legacy, e.getMessage());
            }
        }
    }
}