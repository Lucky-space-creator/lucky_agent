package com.lucky.agent.cli.approval;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.term.InputReader;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ASK 授权交互：把内核「高危操作需确认」翻译成终端上的四档裁决。
 *
 * <p><b>本类修的是当前最严重的安全缺陷</b>（CLI 方案 v1 §3 P0-2）。旧实现确认后回传的是
 * 自行拼接的 {@code {opType:"WRITE", path:"", args:{}}}，与用户实际被询问的操作毫无关系 ——
 * 用户按一次 {@code y}，等于向内核声明「我授权了 WRITE 空路径」。结果是<b>授权范围与所见不符</b>。
 * 本类一律把 {@code ask} 事件里的 {@code op} <b>原样回传</b>；缺少 {@code op} 时按拒绝处理，
 * 绝不猜一个默认操作。</p>
 *
 * <p><b>四档语义</b></p>
 * <ul>
 *   <li>{@code y} 仅本次：把该 op 作为 {@code extra.confirm} 回传，内核一次性放行。</li>
 *   <li>{@code a} 本会话内对该 (操作类型, 路径) 始终允许：<b>仅存内存</b>，进程退出即失效。
 *       键里带操作类型是刻意的 —— 否则「允许写这个文件」会连带放行「删除这个文件」。</li>
 *   <li>{@code r} 写入持久规则：需完整输入 {@code yes} 二次确认，并先打印规则内容。</li>
 *   <li>其它（含直接回车）→ 拒绝，拒绝是默认档。</li>
 * </ul>
 *
 * <p><b>权限不可由此提权</b>：四档都只是「对这一次 ASK 作答」，不放宽工作空间权限级别，
 * 也不绕过执行臂 realpath 硬边界（决策 D2）。CLI 不提供 {@code --dangerously-*} 之类逃逸口。</p>
 */
@Slf4j
public final class ApprovalHandler {

    /** 交互重问上限，防呆循环输入把终端卡死。 */
    private static final int MAX_PROMPT_ATTEMPTS = 5;

    /** 持久规则优先级：低于内置基线（1000），高于默认档（100）。 */
    private static final int CLI_RULE_PRIORITY = 200;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 裁决档位。 */
    public enum Choice {
        /** 仅本次。 */
        ALLOW_ONCE,
        /** 本会话内对该 (操作类型, 路径) 始终允许。 */
        ALLOW_SESSION,
        /** 已写入持久规则。 */
        PERSISTED,
        /** 拒绝。 */
        DENY
    }

    /**
     * 裁决结果。
     *
     * @param choice  档位
     * @param op      被询问的操作（原样）
     * @param confirm 待回传内核的 {@code extra.confirm}；{@code null} 表示不回传（拒绝）
     */
    public record Verdict(Choice choice, Map<String, Object> op, Map<String, Object> confirm) {

        public boolean allowed() {
            return confirm != null && !confirm.isEmpty();
        }

        static Verdict deny(Map<String, Object> op) {
            return new Verdict(Choice.DENY, op, null);
        }
    }

    private final OutputSink out;
    private final InputReader editor;
    private final PermissionService permissionService;
    private final WorkspaceConfig workspaceConfig;
    private final boolean autoApprove;

    /** 会话级允许集：键为 {@code 操作类型\x00归一化路径}。仅内存，进程退出即失效。 */
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    /**
     * @param editor            输入读取器；{@code null} 表示非交互环境（headless / 管道）
     * @param permissionService 权限服务（写入持久规则用；可为 null，此时该档不可用）
     * @param workspaceConfig   工作空间配置（把相对路径解析成绝对路径用；可为 null）
     * @param autoApprove       是否自动放行（显式 opt-in，每次都会打印审计行）
     */
    public ApprovalHandler(OutputSink out, InputReader editor, PermissionService permissionService,
                           WorkspaceConfig workspaceConfig, boolean autoApprove) {
        this.out = out;
        this.editor = editor;
        this.permissionService = permissionService;
        this.workspaceConfig = workspaceConfig;
        this.autoApprove = autoApprove;
    }

    /** 是否可以问用户（自动放行模式下不再询问）。 */
    public boolean interactive() {
        return editor != null && !autoApprove;
    }

    /**
     * 就一次高危操作向用户求裁决。
     *
     * @param op          {@code ask} 事件 payload 中的 {@code op}（必须原样）
     * @param question    内核给出的提问文本
     * @param risk        风险等级
     * @param workspaceId 当前工作空间（解析相对路径用）
     */
    public Verdict ask(Map<String, Object> op, String question, String risk, String workspaceId) {
        if (op == null || op.isEmpty()) {
            out.println(out.theme().red("⚠️ 内核请求授权但未提供操作详情，出于安全考虑按「拒绝」处理。"));
            return Verdict.deny(null);
        }
        String opType = str(op.get("opType"));
        String path = str(op.get("path"));
        printRequest(question, risk, op);

        if (autoApprove) {
            out.println(out.theme().yellow("⏵ 已开启 --auto-approve，自动放行：") + " " + describe(opType, path));
            return new Verdict(Choice.ALLOW_ONCE, op, op);
        }

        String key = sessionKey(opType, path);
        if (key != null && sessionAllowed.contains(key)) {
            out.println(out.theme().dim("（本会话已允许 " + describe(opType, path) + "，自动放行）"));
            return new Verdict(Choice.ALLOW_SESSION, op, op);
        }

        if (editor == null) {
            out.println(out.theme().yellow(
                    "非交互环境无法询问授权 → 按「拒绝」处理。"
                            + "如需脚本化放行，请显式加 --auto-approve（仍受执行臂权限级别约束）。"));
            return Verdict.deny(op);
        }

        for (int i = 0; i < MAX_PROMPT_ATTEMPTS; i++) {
            String line = editor.readLine("请选择 (y=仅本次 / a=本会话允许 / r=持久规则 / N=拒绝): ");
            String choice = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "y", "yes" -> {
                    return new Verdict(Choice.ALLOW_ONCE, op, op);
                }
                case "a" -> {
                    if (key == null) {
                        out.println(out.theme().yellow(
                                "内核未给出操作类型，无法精确记录「本会话允许」范围。请改用 y（仅本次）。"));
                        break;
                    }
                    sessionAllowed.add(key);
                    out.println(out.theme().dim("已记录：本会话内 " + describe(opType, path) + " 不再询问。"));
                    return new Verdict(Choice.ALLOW_SESSION, op, op);
                }
                case "r" -> {
                    if (persistRule(opType, path, workspaceId)) {
                        return new Verdict(Choice.PERSISTED, op, op);
                    }
                    // 未写入则回到选择（用户可能改选 y）
                }
                case "", "n", "no" -> {
                    out.println(out.theme().dim("已拒绝。你可以直接输入修改要求，Agent 会换一种方式继续。"));
                    return Verdict.deny(op);
                }
                default -> out.println(out.theme().yellow("无法识别的选择「" + choice + "」，请重新输入。"));
            }
        }
        out.println(out.theme().yellow("连续多次未做出有效选择，按「拒绝」处理。"));
        return Verdict.deny(op);
    }

    // ------------------------------------------------------------------ 持久规则

    /**
     * 写入持久规则（第三档，需二次确认）。
     *
     * <p><b>为什么用 {@code regex:^…$} 而不是 glob</b>：项目的 PATH 规则走 {@code PathRuleMatcher}，
     * 其 glob 语义里 {@code *}、{@code [} 都是通配符。用户路径一旦含这些字符，glob 会把它解释成
     * <b>更广</b>的范围 —— 那是把「放行这一个文件」悄悄放大成「放行一批文件」。
     * 用 {@link Pattern#quote(String)} 包住整串、加 {@code ^}/{@code $} 锚定，得到零通配语义的精确匹配。</p>
     *
     * <p><b>为什么要把已有规则读回来再合并</b>：{@code PermissionService#saveRules} 是<b>整体替换</b>语义。
     * 直接提交单条新规则会把内存中的内置安全基线（敏感路径 / 破坏性命令 DENY）一并抹掉。
     * 故按 id 去重合并「文件中的规则 + 内存中的规则 + 新规则」。</p>
     */
    private boolean persistRule(String opType, String path, String workspaceId) {
        if (permissionService == null) {
            out.println(out.theme().red("权限服务不可用，无法写入持久规则。"));
            return false;
        }
        PermissionRule rule;
        try {
            rule = buildRule(opType, path, workspaceId);
        } catch (Exception e) {
            out.println(out.theme().red("无法生成持久规则：" + e.getMessage()));
            return false;
        }
        out.println("将写入以下持久规则（已有规则不受影响）：");
        out.println(out.theme().dim(ruleDetail(rule)));
        if (editor == null) {
            out.println(out.theme().yellow("非交互环境无法二次确认，已取消写入。"));
            return false;
        }
        String confirm = editor.readLine("确认写入请输入完整的 yes（其它任意输入取消）: ");
        if (!"yes".equals(confirm == null ? "" : confirm.trim().toLowerCase(Locale.ROOT))) {
            out.println(out.theme().dim("已取消写入持久规则。"));
            return false;
        }
        try {
            permissionService.saveRules(mergeWithNew(
                    permissionService.loadRules(), permissionService.currentRules(), rule));
            out.println(out.theme().green("已写入持久规则：") + out.theme().dim(rule.id()));
            return true;
        } catch (Exception e) {
            log.warn("写入持久规则失败", e);
            out.println(out.theme().red("写入持久规则失败：" + e.getMessage()));
            return false;
        }
    }

    private PermissionRule buildRule(String opType, String path, String workspaceId) {
        String raw = path == null ? "" : path.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("操作未携带路径/命令，无法生成精确规则");
        }
        if ("EXEC".equalsIgnoreCase(opType)) {
            return new PermissionRule()
                    .id("cli.allow-command-" + UUID.randomUUID())
                    .priority(CLI_RULE_PRIORITY)
                    .type(PermissionRule.RuleType.COMMAND)
                    .matcher(new PermissionRule.Matcher(exactRegex(raw), PermissionRule.Anchor.RELATIVE.code()))
                    .action(PermissionRule.RuleAction.ALLOW)
                    .reason("CLI 用户授权（" + STAMP.format(Instant.now()) + "）");
        }
        Path abs = resolveAgainstWorkspace(raw, workspaceId);
        return new PermissionRule()
                .id("cli.allow-path-" + UUID.randomUUID())
                .priority(CLI_RULE_PRIORITY)
                .type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher(
                        exactRegex(abs.toString().replace('\\', '/')), PermissionRule.Anchor.ABS.code()))
                .action(PermissionRule.RuleAction.ALLOW)
                .reason("CLI 用户授权（" + STAMP.format(Instant.now()) + "）");
    }

    /**
     * 把操作路径归一成「裁决器会算出的那个绝对路径」。
     *
     * <p>必须严格镜像 {@code PermissionEvaluator#evaluate} 的算法，否则写出的规则永远匹配不上：
     * 它先 {@code replaceAll("^[/\\\\]+", "")} 去掉所有前导斜杠（<b>连绝对路径也会变成相对</b>），
     * 再用 {@code workspaceRoot.resolve(opPath).normalize()} 求绝对路径。故这里不能图省事用
     * {@code Path.of(raw).isAbsolute()} 分支处理 —— 那样绝对输入会得到与裁决器不同的结果。</p>
     */
    private Path resolveAgainstWorkspace(String raw, String workspaceId) {
        String opPath = raw.replaceAll("^[/\\\\]+", "");
        Optional<String> root = workspaceConfig == null || workspaceId == null
                ? Optional.empty() : workspaceConfig.physicalPathOf(workspaceId);
        Path workspaceRoot = root.map(Path::of).orElseGet(() -> Path.of("").toAbsolutePath());
        return workspaceRoot.resolve(opPath).normalize();
    }

    /** 精确匹配正则：{@code regex:^<quote(text)>$}。 */
    private static String exactRegex(String text) {
        return "regex:^" + Pattern.quote(text) + "$";
    }

    /** 按 id 去重合并「两份已有规则 + 一条新规则」（同 id 先出现者优先；无 id 者全部保留）。 */
    private static List<PermissionRule> mergeWithNew(List<PermissionRule> first, List<PermissionRule> second,
                                                     PermissionRule added) {
        Map<String, PermissionRule> byId = new LinkedHashMap<>();
        int anonymous = 0;
        List<List<PermissionRule>> sources = List.of(first, second, List.of(added));
        for (List<PermissionRule> src : sources) {
            for (PermissionRule r : src) {
                if (r == null) {
                    continue;
                }
                String id = r.id();
                byId.putIfAbsent(id == null || id.isBlank() ? "__anon_" + (anonymous++) : id, r);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String ruleDetail(PermissionRule r) {
        return "  类型=" + r.type() + "  动作=" + r.action() + "  优先级=" + r.priority()
                + "\n  匹配=" + (r.matcher() == null ? "-" : r.matcher().pattern())
                + "  锚点=" + (r.matcher() == null ? "-" : r.matcher().anchor())
                + "\n  规则 ID=" + r.id();
    }

    // ------------------------------------------------------------------ 展示

    private void printRequest(String question, String risk, Map<String, Object> op) {
        out.println();
        out.println(out.theme().danger(" ⚠️ 需要授权 ") + " "
                + (question == null || question.isBlank() ? "高危操作需你确认" : question));
        out.println("   操作：" + out.theme().bold(str(op.get("opType")))
                + "   路径：" + str(op.get("path")));
        Object args = op.get("args");
        if (args != null) {
            out.println("   参数：" + out.theme().dim(abbreviate(String.valueOf(args), 240)));
        }
        if (risk != null && !risk.isBlank()) {
            out.println("   风险：" + out.theme().yellow(risk));
        }
    }

    private static String describe(String opType, String path) {
        return (opType == null || opType.isBlank() ? "?" : opType) + " " + (path == null ? "" : path);
    }

    /** 会话允许键：包含操作类型 + 归一化路径（大小写、分隔符归一）。 */
    private static String sessionKey(String opType, String path) {
        if (opType == null || opType.isBlank()) {
            return null;
        }
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT).trim();
        return opType.toUpperCase(Locale.ROOT) + "\u0000" + normalized;
    }

    private static String abbreviate(String text, int limit) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
