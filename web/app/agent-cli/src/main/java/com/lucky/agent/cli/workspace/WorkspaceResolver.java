package com.lucky.agent.cli.workspace;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 工作空间解析：把 {@code -w <ID(含前 8 位短 ID)|名称|路径>} 或「自动挑选」解析为唯一工作空间。
 *
 * <p><b>修掉的两个既有隐患</b>（原实现直接取列表第一个，无任何提示）：</p>
 * <ol>
 *   <li><b>静默选择</b>：选中哪个工作空间用户完全不知道。一旦 Agent 在错误的目录里操作，
 *       排查成本极高。故本类返回的选择结果由调用方<b>无条件回显</b>（名称 / ID / 权限 / 路径）。</li>
 *   <li><b>可能落进内置默认工作空间</b>：{@code Workspace.builtin=true} 的内置工作空间物理路径位于
 *       <b>框架根内</b>（{@code ~/.lucky_agent/workspace}），而框架根下放着 {@code config/}、
 *       {@code memory/}、{@code skills/}、{@code sessions/}。故自动挑选时<b>排除内置</b>，
 *       仅当用户未自建任何工作空间时才回退到它。</li>
 * </ol>
 *
 * <p><b>多候选不猜</b>：若存在多个同等候选且用户未指定 {@code -w}，返回
 * {@link Status#AMBIGUOUS} 并列出候选 —— 交由交互式会话询问。</p>
 *
 * <p><b>兜底而不是退出</b>：当无法询问（非 TTY）或用户放弃选择时，调用方应使用
 * {@link #builtinDefault(WorkspaceConfig)} 回落到内置默认工作空间并<b>大声回显</b>，
 * 而不是让 CLI 直接以用法错误退出。「没选也必须有一个默认落点」是可用性底线；
 * 但回落必须显式告知，否则就成了它极力避免的「静默选择」。</p>
 */
public final class WorkspaceResolver {

    /** 解析结果状态。 */
    public enum Status {
        /** 唯一命中。 */
        OK,
        /** 系统内没有任何工作空间。 */
        EMPTY,
        /** 指定了选择器但无匹配。 */
        NOT_FOUND,
        /** 命中多条，需用户明确指定。 */
        AMBIGUOUS
    }

    /**
     * @param workspace  命中的工作空间（仅 {@link Status#OK} 时非空）
     * @param status     状态
     * @param candidates 候选列表（{@link Status#AMBIGUOUS} 时用于提示）
     */
    public record Result(Workspace workspace, Status status, List<Workspace> candidates) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private WorkspaceResolver() {
    }

    /**
     * 解析工作空间。
     *
     * @param config   工作空间配置中心
     * @param selector {@code -w} 取值；为空表示自动挑选
     */
    public static Result resolve(WorkspaceConfig config, String selector) {
        List<Workspace> all = config == null ? List.of() : config.listWorkspaces();
        if (all == null || all.isEmpty()) {
            return new Result(null, Status.EMPTY, List.of());
        }
        if (selector == null || selector.isBlank()) {
            return pickDefault(all);
        }
        List<Workspace> matched = match(all, selector.trim());
        if (matched.isEmpty()) {
            return new Result(null, Status.NOT_FOUND, List.of());
        }
        if (matched.size() > 1) {
            return new Result(null, Status.AMBIGUOUS, List.copyOf(matched));
        }
        return new Result(matched.get(0), Status.OK, List.of(matched.get(0)));
    }

    /**
     * 自动挑选：优先「可写」的非内置工作空间；排除内置；仍无唯一者则报歧义。
     *
     * <p>优先级：非内置且权限为 FULL/MODIFY → 非内置（任意权限）→ 内置（兜底）。</p>
     */
    private static Result pickDefault(List<Workspace> all) {
        List<Workspace> candidates = new ArrayList<>(all.stream()
                .filter(w -> !w.builtin() && isWritable(w)).toList());
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(all.stream().filter(w -> !w.builtin()).toList());
        }
        if (candidates.isEmpty()) {
            // 仅存在内置工作空间：兜底可用，但调用方必须回显提示，让用户知道当前落点
            candidates = new ArrayList<>(all);
        }
        if (candidates.size() > 1) {
            return new Result(null, Status.AMBIGUOUS, List.copyOf(candidates));
        }
        return new Result(candidates.get(0), Status.OK, List.copyOf(candidates));
    }

    /**
     * 按序号取工作空间（1-based，与 {@code /workspace} 无参列表的编号一致）。
     *
     * <p>为什么需要：{@code /workspace} 无参时展示的是 {@code 1./2./3.} 编号，但切换到
     * {@code <ID|名称|路径>} 却只认长 ID —— 用户照着列表敲 {@code /workspace 2} 必然
     * {@link Status#NOT_FOUND}，提示与实现自相矛盾。序号与列表同源，消除该矛盾。</p>
     *
     * @param all   全量工作空间（顺序即展示顺序）
     * @param token 用户输入
     * @return 命中的工作空间；token 非纯数字或越界时为空（交由 ID/名称/路径匹配继续判断）
     */
    public static Optional<Workspace> byIndex(List<Workspace> all, String token) {
        if (all == null || all.isEmpty() || token == null || token.isBlank()
                || !token.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            int idx = Integer.parseInt(token);
            return idx >= 1 && idx <= all.size() ? Optional.of(all.get(idx - 1)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 内置默认工作空间（{@code builtin=true}，物理路径位于框架根内
     * {@code <user.home>/.lucky_agent/workspace}）。
     *
     * <p><b>为什么需要它</b>：多候选且无法询问（非 TTY、管道、或用户直接放弃）时，
     * 若就地报错退出，用户连 CLI 都进不去 —— 这与「必须有一个默认落点」的可用性底线冲突。
     * 故此处提供明确的兜底落点：<b>代码里没有工作空间，但框架本机一定有自己的目录</b>。</p>
     *
     * <p>注意调用方<b>必须回显</b>这是兜底选择：本方法只负责取，不负责告知用户。</p>
     *
     * @return 内置默认工作空间；系统内无内置项时为空
     */
    public static Optional<Workspace> builtinDefault(WorkspaceConfig config) {
        List<Workspace> all = config == null ? List.of() : config.listWorkspaces();
        if (all == null || all.isEmpty()) {
            return Optional.empty();
        }
        return all.stream().filter(Workspace::builtin).findFirst();
    }

    /**
     * 匹配选择器：{@code workspaceId} 精确 → 名称精确 → ID 前缀（含 8 位短 ID）→ 名称包含 → 物理路径归一后相等。
     *
     * <p>{@code WorkspaceConfig} 契约不提供按名/路径查找，故此处自行匹配（契约只暴露
     * {@code listWorkspaces()} 等按 ID 查询的方法）。</p>
     *
     * <p><b>为何必须支持 ID 前缀</b>：歧义提示（与 {@code /workspace}）展示的是
     * {@link #describe} 里的 <b>8 位短 ID</b>。若只认完整 UUID，用户照着提示敲
     * {@code -w 859c4350} 必然得 {@link Status#NOT_FOUND} —— 提示就成了「不可用的指引」。
     * 前缀命中多条时自然回落为 {@link Status#AMBIGUOUS}，不会误选。</p>
     */
    private static List<Workspace> match(List<Workspace> all, String selector) {
        List<Workspace> exactId = all.stream().filter(w -> selector.equals(w.workspaceId())).toList();
        if (!exactId.isEmpty()) {
            return exactId;
        }
        List<Workspace> exactName = all.stream()
                .filter(w -> w.name() != null && selector.equalsIgnoreCase(w.name())).toList();
        if (!exactName.isEmpty()) {
            return exactName;
        }
        // ID 前缀（覆盖 8 位短 ID）；大小写不敏感，UUID 十六进制无大小写之分但用户可能复制出大写
        String lcSelector = selector.toLowerCase(Locale.ROOT);
        List<Workspace> idPrefix = all.stream()
                .filter(w -> w.workspaceId() != null
                        && w.workspaceId().toLowerCase(Locale.ROOT).startsWith(lcSelector))
                .toList();
        if (!idPrefix.isEmpty()) {
            return idPrefix;
        }
        List<Workspace> fuzzyName = all.stream()
                .filter(w -> w.name() != null
                        && w.name().toLowerCase(Locale.ROOT).contains(lcSelector))
                .toList();
        if (!fuzzyName.isEmpty()) {
            return fuzzyName;
        }
        String normalized = normalizePath(selector);
        return all.stream()
                .filter(w -> w.path() != null && normalizePath(w.path()).equals(normalized))
                .toList();
    }

    /** 路径归一：绝对化 + 规范化（Windows 下不区分大小写比较）。 */
    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String n = Path.of(raw).toAbsolutePath().normalize().toString();
        return n.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean isWritable(Workspace w) {
        PermissionLevel level = w.permissionLevel();
        return level == PermissionLevel.FULL || level == PermissionLevel.MODIFY;
    }

    /** 单行摘要（供启动 Banner 与 {@code /workspace} 回显；本类不直接输出，保持可测）。 */
    public static String describe(Workspace w) {
        if (w == null) {
            return "(未选中)";
        }
        PermissionLevel level = w.permissionLevel() == null
                ? PermissionLevel.defaultValue() : w.permissionLevel();
        return (w.name() == null ? "(未命名)" : w.name())
                + " [" + shortId(w.workspaceId()) + "]"
                + " · 权限 " + level.getLabel()
                + " · " + (w.path() == null ? "(无路径)" : w.path())
                + (w.builtin() ? " · 内置默认" : "");
    }

    private static String shortId(String id) {
        return id == null ? "-" : (id.length() > 8 ? id.substring(0, 8) : id);
    }
}
