package com.lucky.agent.memory.util;

import com.lucky.agent.workspace.api.WorkspaceConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 记忆磁盘目录命名（用户级在 memory/ 根下；本地级按工作空间<b>物理全路径</b>转义命名，
 * 对标 Claude Code {@code projects/<路径转义>/}，跨项目不串扰、目录可读可审计）。
 *
 * <p>转义规则：保留 Unicode 字母/数字/下划线/连字符，其余（含 {@code \ : . 空格}）替换为
 * {@code _}，并去掉首尾下划线。例：{@code C:\Users\LENOVO\新项目} → {@code C_Users_LENOVO_新项目}。</p>
 *
 * <p><b>为什么还要加摘要</b>：转义是<b>有损</b>的 —— 分隔符 {@code \ / :}、空格、{@code _}、{@code -}
 * 全部塌缩成同一个字符，于是 {@code C:\a\b} 与 {@code C:\a_b}（以及 {@code a-b} / {@code a b}）
 * 会算出同一个目录名，两个工作空间就会共用一份记忆、互相覆盖。因此目录名固定为
 * <b>可读前缀 + {@code -} + 身份摘要</b>（{@link #digest}，SHA-256 前 8 位 hex）：
 * 前缀保留可读可审计，摘要由<b>原始字符串</b>（物理路径，缺失时退化用 workspaceId）算出，
 * 不同身份必然得到不同目录，因转义塌缩而串写的路径被彻底切断。</p>
 *
 * <p><b>存量迁移</b>：历史上有过两种命名，访问时按「新近优先」逐个尝试搬迁，均不存在则新建：</p>
 * <ol>
 *   <li>{@code escape(物理路径)} —— 上一版「全路径转义」命名（有碰撞风险那一版）；</li>
 *   <li>{@code sanitize(workspaceId)} —— 最初版本命名（{@link #legacyName}）。</li>
 * </ol>
 * <p>迁移原子优先、失败不阻断；读取侧仍双目录兜底，保证不丢存量记忆。
 * 注意：若历史上确有两个工作空间撞名共用一个目录，该目录只会被<b>先访问</b>的那个继承，
 * 另一个从空目录重新开始 —— 共享停止即是修复目标，被污染的历史数据无法自动拆分。</p>
 */
@Slf4j
public class WorkspaceMemoryPaths {

    /** 工作空间无法解析物理路径时回退的默认目录名。 */
    public static final String DEFAULT_DIR = "default";

    /** 目录名摘要长度（hex 字符数）。8 hex = 32 bit，本机工作空间规模下碰撞概率可忽略。 */
    private static final int DIGEST_LEN = 8;

    private final WorkspaceConfig workspaceConfig;

    public WorkspaceMemoryPaths(WorkspaceConfig workspaceConfig) {
        this.workspaceConfig = workspaceConfig;
    }

    /**
     * 工作空间记忆目录名：可读前缀（优先「物理全路径转义」，无法解析时用 workspaceId）+ 身份摘要。
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
                    String raw = path.get();
                    return identityName(escape(raw), raw);
                }
            } catch (Exception e) {
                log.warn("解析工作空间物理路径失败，回退按 ID 命名：ws={} err={}", workspaceId, e.getMessage());
            }
        }
        return identityName(legacyName(workspaceId), workspaceId);
    }

    /**
     * 解析（必要时迁移）某个工作空间的本地级记忆目录。
     * <p>目标目录 = {@code parent}/{@link #dirName(workspaceId)}；若历史命名目录存在且新目录
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
        String freshName = dirName(workspaceId) + sfx;
        Path fresh = parent.resolve(freshName);
        if (Files.exists(fresh)) {
            return fresh;
        }
        for (String oldName : legacyNames(workspaceId)) {
            String candidate = oldName + sfx;
            if (candidate.equals(freshName)) {
                continue;
            }
            Path legacy = parent.resolve(candidate);
            if (Files.isDirectory(legacy)) {
                migrate(legacy, fresh);
                break;
            }
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

    /** 路径转义（保留 Unicode 字母/数字/_/-，其余替换 _，去首尾 _）。**有损**，仅作可读前缀。 */
    public static String escape(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DIR;
        }
        String escaped = raw.replaceAll("[^\\p{L}\\p{N}_-]+", "_").replaceAll("^_+|_+$", "");
        return escaped.isEmpty() ? DEFAULT_DIR : escaped;
    }

    /**
     * 目录名 = 可读前缀 + {@code -} + 身份摘要。
     *
     * @param base     可读前缀（{@link #escape} 或 {@link #legacyName} 的结果）
     * @param identity 原始身份串（物理路径或 workspaceId），摘要只认它
     * @return 单射的目录名
     */
    static String identityName(String base, String identity) {
        return base + "-" + digest(identity);
    }

    /** 稳定摘要：SHA-256 前 {@link #DIGEST_LEN} 位 hex；算法不可用时退化为 hashCode（仍保证同名同值）。 */
    static String digest(String identity) {
        String raw = identity == null ? "" : identity;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * DIGEST_LEN);
            for (int i = 0; i < hash.length && sb.length() < DIGEST_LEN; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16))
                        .append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.substring(0, DIGEST_LEN);
        } catch (NoSuchAlgorithmException e) {
            String fallback = String.format("%08x", raw.hashCode());
            return fallback.substring(0, DIGEST_LEN);
        }
    }

    /** 历史命名候选（新近优先）：全路径转义 → workspaceId sanitize。 */
    private List<String> legacyNames(String workspaceId) {
        List<String> names = new ArrayList<>(2);
        if (workspaceConfig != null) {
            try {
                workspaceConfig.physicalPathOf(workspaceId)
                        .filter(p -> !p.isBlank())
                        .map(WorkspaceMemoryPaths::escape)
                        .ifPresent(names::add);
            } catch (Exception e) {
                log.warn("解析工作空间物理路径失败，跳过转义命名迁移：ws={} err={}", workspaceId, e.getMessage());
            }
        }
        names.add(legacyName(workspaceId));
        return names;
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
