package com.lucky.agent.cli.command;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.repl.StatusBar;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.cli.workspace.WorkspaceResolver;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.core.repository.SessionRepository;
import com.lucky.agent.workspace.api.dto.Workspace;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * slash 命令注册表与内置命令。
 *
 * <p><b>能力边界（决策 1）</b>：CLI 不接工作流。这里既没有 {@code /run} 也没有 {@code /workflows}，
 * 且 {@code agent-cli/pom.xml} 用 enforcer 禁止依赖 {@code agent-workflow} —— 从"实现"和"构建"
 * 两层同时锁死，避免日后顺手把工作流引擎的 Bean 拉进 CLI 上下文造成产品边界漂移。</p>
 *
 * <p>命令一律只做<b>查看</b>与<b>会话管理</b>：不直接执行文件操作、不接触权限链 ——
 * 需要真实动作的场景（执行命令、读文件）走 {@code !} / {@code @} 前缀，由它们交给执行臂。</p>
 */
@Slf4j
public final class SlashCommandRegistry {

    /** 分发结果。 */
    public enum Outcome {
        /** 已处理，继续 REPL。 */
        CONTINUE,
        /** 已处理，请求退出 REPL。 */
        EXIT,
        /** 不是 slash 命令（调用方按普通输入处理）。 */
        NOT_A_COMMAND,
        /** 是 slash 命令但未知/参数错误。 */
        UNKNOWN
    }

    /** 前缀字符。 */
    public static final char PREFIX = '/';

    private final Map<String, SlashCommand> byName = new LinkedHashMap<>();

    private SlashCommandRegistry() {
    }

    /** 构建含全部内置命令的注册表。 */
    public static SlashCommandRegistry withBuiltins() {
        SlashCommandRegistry r = new SlashCommandRegistry();
        r.register(new HelpCommand());
        r.register(new ExitCommand());
        r.register(new NewCommand());
        r.register(new SessionsCommand());
        r.register(new ResumeCommand());
        r.register(new HistoryCommand());
        r.register(new WorkspaceCommand());
        r.register(new TokensCommand());
        r.register(new DoctorCommand());
        r.register(new ClearCommand());
        return r;
    }

    public void register(SlashCommand command) {
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            byName.putIfAbsent(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    /** 全部命令（按注册顺序，用于 {@code /help}）。 */
    public List<SlashCommand> commands() {
        return byName.values().stream().distinct().toList();
    }

    /** 补全候选：所有以 prefix 开头的命令名（含别名与前导斜杠）。 */
    public List<String> completions(String prefix) {
        String p = prefix == null ? "/" : prefix.toLowerCase(Locale.ROOT);
        return byName.keySet().stream()
                .map(name -> "/" + name)
                .filter(s -> s.startsWith(p))
                .sorted()
                .toList();
    }

    /**
     * 分发一行 slash 命令。
     *
     * @return {@link Outcome#NOT_A_COMMAND} 表示该行不是 slash 命令
     */
    public Outcome dispatch(String line, CommandContext ctx) {
        if (line == null || line.isEmpty() || line.charAt(0) != PREFIX) {
            return Outcome.NOT_A_COMMAND;
        }
        // 兜底回填：/help 需要注册表才能列举命令。这里自填一次，就把「先有上下文还是先有注册表」
        // 的时序依赖彻底消掉——调用方无论按什么顺序装配都不会让 /help 变成 NPE。
        if (ctx.registry() == null) {
            ctx.registry(this);
        }
        String[] parts = line.substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            ctx.out().println(ctx.out().theme().yellow("请输入命令，例如 /help"));
            return Outcome.UNKNOWN;
        }
        String name = parts[0].toLowerCase(Locale.ROOT);
        SlashCommand cmd = byName.get(name);
        if (cmd == null) {
            ctx.out().println(ctx.out().theme().yellow("未知命令 /" + name
                    + "，输入 /help 查看可用命令。"));
            return Outcome.UNKNOWN;
        }
        List<String> args = parts.length <= 1
                ? List.of() : List.of(Arrays.copyOfRange(parts, 1, parts.length));
        boolean exit = cmd.run(ctx, args);
        return exit ? Outcome.EXIT : Outcome.CONTINUE;
    }

    // ================================================================== 内置命令

    /** {@code /help} —— 列出全部命令与能力边界说明。 */
    private static final class HelpCommand implements SlashCommand {

        @Override
        public String name() {
            return "help";
        }

        @Override
        public List<String> aliases() {
            return List.of("?", "h");
        }

        @Override
        public String summary() {
            return "显示本帮助";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            out.println(out.theme().bold("命令（全部以 / 开头）"));
            for (SlashCommand c : ctx.registry().commands()) {
                String alias = c.aliases().isEmpty() ? "" : out.theme().dim("（别名 /" + String.join(" /", c.aliases()) + "）");
                out.println("  " + pad(c.usage(), 26) + c.summary() + " " + alias);
            }
            out.println();
            out.println(out.theme().bold("输入前缀"));
            out.println("  " + pad("!<命令>", 26) + "直接交给执行臂执行，不经模型（仍受权限与边界校验）");
            out.println("  " + pad("@<路径> [说明]", 26) + "把文件内容注入本轮上下文（经执行臂读取）");
            out.println("  " + pad("其它任意文本", 26) + "作为提示词发给 Agent");
            out.println();
            out.println(out.theme().dim("  运行中按 Ctrl+C 可优雅取消本轮；提示符处连按两次 Ctrl+C 退出。"));
            out.println(out.theme().dim("  工作流不在 CLI 能力范围内（CLI 只承载 Agent 编排）。"));
            return false;
        }

        private static String pad(String s, int width) {
            if (s.length() >= width) {
                return s + "  ";
            }
            return s + " ".repeat(width - s.length());
        }
    }

    /** {@code /exit} —— 退出。 */
    private static final class ExitCommand implements SlashCommand {

        @Override
        public String name() {
            return "exit";
        }

        @Override
        public List<String> aliases() {
            return List.of("quit", "q");
        }

        @Override
        public String summary() {
            return "退出 CLI";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            ctx.requestExit();
            return true;
        }
    }

    /** {@code /new} —— 新会话（同工作空间）。 */
    private static final class NewCommand implements SlashCommand {

        @Override
        public String name() {
            return "new";
        }

        @Override
        public String summary() {
            return "开始一个新会话（沿用当前工作空间）";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            SessionHolder holder = ctx.holder();
            Map<String, Object> created;
            try {
                // 走内核 createSession：立即落盘元数据，用户 /sessions 就能看到它
                // （标题用「新会话」占位，首条消息后内核会按既有 needsTitle 规则自动重新命名）
                created = ctx.services().conversations()
                        .createSession(holder.userId(), holder.workspaceId(), null).block();
            } catch (Exception e) {
                out.println(out.theme().red("新建会话失败：" + e.getMessage()));
                return false;
            }
            String id = created == null ? null : String.valueOf(created.get("sessionId"));
            if (id == null || id.isBlank() || "null".equals(id)) {
                out.println(out.theme().red("新建会话失败：内核未返回会话 ID。"));
                return false;
            }
            holder.ref(new SessionRef(id, holder.userId(), holder.workspaceId()));
            out.println(out.theme().green("已开启新会话 ") + out.theme().dim(holder.shortId()));
            return false;
        }
    }

    /** {@code /sessions} —— 列出会话。 */
    private static final class SessionsCommand implements SlashCommand {

        private static final int LIMIT = 20;

        @Override
        public String name() {
            return "sessions";
        }

        @Override
        public List<String> aliases() {
            return List.of("ls");
        }

        @Override
        public String summary() {
            return "列出最近的会话（1 为最近）";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            List<SessionRepository.SessionMeta> all = ctx.services().sessions().list(ctx.holder().userId());
            if (all.isEmpty()) {
                out.println(out.theme().dim("还没有任何会话。直接输入提示词即可开始。"));
                return false;
            }
            out.println(out.theme().bold("会话（共 " + all.size() + " 个，显示前 " + Math.min(LIMIT, all.size()) + " 个）"));
            String current = ctx.holder().sessionId();
            for (int i = 0; i < Math.min(LIMIT, all.size()); i++) {
                SessionRepository.SessionMeta m = all.get(i);
                String mark = m.sessionId().equals(current) ? out.theme().green("●") : " ";
                out.println(mark + " " + ctx.services().sessions().format(m, i + 1));
            }
            out.println(out.theme().dim("用 /resume <序号|ID|ID前缀> 恢复指定会话。"));
            return false;
        }
    }

    /** {@code /resume} —— 恢复历史会话。 */
    private static final class ResumeCommand implements SlashCommand {

        @Override
        public String name() {
            return "resume";
        }

        @Override
        public String usage() {
            return "/resume <序号|ID|ID前缀>";
        }

        @Override
        public String summary() {
            return "恢复历史会话（历史由内核从磁盘回灌）";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            if (args.isEmpty()) {
                out.println(out.theme().yellow("用法：" + usage() + "；先 /sessions 看列表。"));
                return false;
            }
            String selector = args.get(0);
            Optional<SessionRepository.SessionMeta> found =
                    ctx.services().sessions().resolve(selector, ctx.holder().userId());
            if (found.isEmpty()) {
                if (ctx.services().sessions().isAmbiguous(selector, ctx.holder().userId())) {
                    out.println(out.theme().yellow("选择器「" + selector + "」命中多个会话，请输入更长的 ID 前缀。"));
                } else {
                    out.println(out.theme().yellow("未找到匹配的会话：「" + selector + "」。"));
                }
                return false;
            }
            SessionRepository.SessionMeta meta = found.get();
            String ws = meta.workspaceId() == null || meta.workspaceId().isBlank()
                    ? ctx.holder().workspaceId() : meta.workspaceId();
            ctx.holder().ref(new SessionRef(meta.sessionId(), ctx.holder().userId(), ws));

            out.println(out.theme().green("已恢复会话 ") + out.theme().dim(StatusBar.shortId(meta.sessionId()))
                    + "  " + out.theme().dim(meta.title() == null ? "" : meta.title()));
            // 回显最近几条，让用户确认恢复对了会话；内容由内核从磁盘读取，通道不缓存
            HistoryCommand.printTail(ctx, 4);
            return false;
        }
    }

    /** {@code /history} —— 查看当前会话最近消息。 */
    private static final class HistoryCommand implements SlashCommand {

        private static final int DEFAULT_LIMIT = 10;

        @Override
        public String name() {
            return "history";
        }

        @Override
        public String usage() {
            return "/history [条数]";
        }

        @Override
        public String summary() {
            return "查看当前会话的最近消息";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            int limit = DEFAULT_LIMIT;
            if (!args.isEmpty()) {
                try {
                    limit = Math.max(1, Math.min(200, Integer.parseInt(args.get(0))));
                } catch (NumberFormatException e) {
                    ctx.out().println(ctx.out().theme().yellow("条数应为数字，已使用默认 " + DEFAULT_LIMIT + "。"));
                }
            }
            printTail(ctx, limit);
            return false;
        }

        /**
         * 打印尾部若干条消息。
         *
         * <p>只读 {@code loadMessages}（不含思考链）。推理产物不外泄是已锁定的通道隔离契约，
         * 由 {@code SessionRepositoryReplayTest} 守住 —— 通道侧一旦展示 thinking，
         * 就等于把「会话回放」与「推理链外泄」两条路径打通了。</p>
         */
        static void printTail(CommandContext ctx, int limit) {
            OutputSink out = ctx.out();
            List<SessionSnapshot.MessageRecord> msgs =
                    ctx.services().sessions().history(ctx.holder().sessionId());
            if (msgs.isEmpty()) {
                out.println(out.theme().dim("当前会话还没有历史消息。"));
                return;
            }
            int from = Math.max(0, msgs.size() - limit);
            out.println(out.theme().dim("── 最近 " + (msgs.size() - from) + " 条（共 " + msgs.size() + " 条）──"));
            for (int i = from; i < msgs.size(); i++) {
                SessionSnapshot.MessageRecord m = msgs.get(i);
                boolean user = "user".equalsIgnoreCase(m.role());
                String who = user ? out.theme().cyan("你") : out.theme().magenta("Agent");
                out.println(who + "  " + oneLine(m.content(), 200));
            }
        }

        private static String oneLine(String text, int limit) {
            if (text == null) {
                return "";
            }
            String flat = text.replaceAll("\\s+", " ").trim();
            return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
        }
    }

    /** {@code /workspace} —— 查看或切换工作空间。 */
    private static final class WorkspaceCommand implements SlashCommand {

        @Override
        public String name() {
            return "workspace";
        }

        @Override
        public List<String> aliases() {
            return List.of("ws");
        }

        @Override
        public String usage() {
            return "/workspace [ID|名称|路径]";
        }

        @Override
        public String summary() {
            return "查看当前工作空间；带参数则切换（会开新会话）";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            var workspaces = ctx.services().workspaces();
            String currentId = ctx.holder().workspaceId();

            if (args.isEmpty()) {
                out.println("当前工作空间：" + out.theme().bold(currentWorkspaceName(ctx)));
                out.println(out.theme().bold("可用工作空间"));
                int i = 1;
                for (Workspace w : workspaces.listWorkspaces()) {
                    String mark = w.workspaceId().equals(currentId) ? out.theme().green("●") : " ";
                    out.println(mark + String.format(" %d. %s", i++, WorkspaceResolver.describe(w)));
                }
                out.println(out.theme().dim("切换：/workspace <序号外的 ID|名称|路径>（会给新工作空间开一个新会话）"));
                return false;
            }

            WorkspaceResolver.Result r = WorkspaceResolver.resolve(workspaces, args.get(0));
            switch (r.status()) {
                case EMPTY -> out.println(out.theme().yellow("系统内没有任何工作空间。"));
                case NOT_FOUND -> out.println(out.theme().yellow("未找到匹配的工作空间：「" + args.get(0) + "」。"));
                case AMBIGUOUS -> {
                    out.println(out.theme().yellow("「" + args.get(0) + "」命中多个工作空间，请用更精确的 ID："));
                    for (Workspace w : r.candidates()) {
                        out.println("   " + WorkspaceResolver.describe(w));
                    }
                }
                case OK -> switchTo(ctx, r.workspace());
            }
            return false;
        }

        private String currentWorkspaceName(CommandContext ctx) {
            return ctx.services().workspaces().getWorkspace(ctx.holder().workspaceId())
                    .map(WorkspaceResolver::describe)
                    .orElse("(未知，会话绑定的工作空间已不存在)");
        }

        /**
         * 切换工作空间 = 新开会话。
         *
         * <p>为什么不改当前会话的 workspaceId：内核 {@code updateMeta} 明确拒绝「已有对话记录的会话
         * 切换工作空间」（历史与目录归属会错乱）。CLI 与其制造一个会被内核拒绝的请求，
         * 不如直接把语义定成「切换即开新会话」并说清楚 —— 用户对结果的预期与实际一致。</p>
         */
        private void switchTo(CommandContext ctx, Workspace w) {
            OutputSink out = ctx.out();
            Map<String, Object> created;
            try {
                created = ctx.services().conversations()
                        .createSession(ctx.holder().userId(), w.workspaceId(), null).block();
            } catch (Exception e) {
                out.println(out.theme().red("切换失败：" + e.getMessage()));
                return;
            }
            String id = created == null ? null : String.valueOf(created.get("sessionId"));
            if (id == null || id.isBlank() || "null".equals(id)) {
                out.println(out.theme().red("切换失败：内核未返回会话 ID。"));
                return;
            }
            ctx.holder().ref(new SessionRef(id, ctx.holder().userId(), w.workspaceId()));
            out.println(out.theme().green("已切换工作空间：") + WorkspaceResolver.describe(w));
            out.println(out.theme().dim("  已为新工作空间开启新会话 " + ctx.holder().shortId()
                    + "（原会话仍保留，可用 /resume 找回）"));
        }
    }

    /** {@code /tokens} —— 用量与耗时。 */
    private static final class TokensCommand implements SlashCommand {

        @Override
        public String name() {
            return "tokens";
        }

        @Override
        public List<String> aliases() {
            return List.of("usage");
        }

        @Override
        public String summary() {
            return "查看最近一轮的 token / 工具 / 缓存用量";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            Map<String, Object> snap;
            try {
                // MetricsCollector#snapshot 对未知会话返回「空指标」而不是 null，
                // 故不能用 null 判空，只能按「有没有真实调用」判定。
                snap = ctx.services().metrics().snapshot(ctx.holder().sessionId()).snapshot();
            } catch (Exception e) {
                log.debug("读取会话指标失败：{}", e.getMessage());
                snap = null;
            }
            if (snap == null || !hasActivity(snap)) {
                out.println(out.theme().dim("当前会话还没有运行记录。"));
                return false;
            }
            out.println(out.theme().bold("最近一轮用量"));
            print(out, "token", snap.get("tokenUsed") + "（输入 " + snap.get("inputTokens")
                    + " / 输出 " + snap.get("outputTokens") + "）");
            print(out, "模型调用", snap.get("modelCalls"));
            print(out, "工具", "成功 " + snap.get("toolOk") + " / 失败 " + snap.get("toolFail"));
            print(out, "缓存", "命中 " + snap.get("cacheHits") + " / 未命中 " + snap.get("cacheMisses")
                    + "（命中率 " + snap.get("cacheHitRate") + "）");
            print(out, "Skill/MCP", snap.get("skillInvokes") + " / " + snap.get("mcpInvokes"));
            print(out, "错误", snap.get("errors"));
            print(out, "最近模型", snap.get("lastModel"));
            Object elapsed = snap.get("elapsedMs");
            if (elapsed instanceof Number n) {
                print(out, "耗时", StatusBar.formatDuration(n.longValue()));
            }
            return false;
        }

        private static void print(OutputSink out, String label, Object value) {
            out.println("  " + pad(label) + (value == null ? "-" : value));
        }

        /** 是否有真实活动（避免全新会话显示一排 0 造成「配置错了」的误解）。 */
        private static boolean hasActivity(Map<String, Object> snap) {
            return positive(snap.get("tokenUsed")) || positive(snap.get("modelCalls"))
                    || positive(snap.get("toolOk")) || positive(snap.get("toolFail"));
        }

        private static boolean positive(Object v) {
            return v instanceof Number n && n.longValue() > 0;
        }

        private static String pad(String label) {
            return label + " ".repeat(Math.max(1, 10 - label.length()));
        }
    }

    /** {@code /doctor} —— 环境自检。 */
    private static final class DoctorCommand implements SlashCommand {

        @Override
        public String name() {
            return "doctor";
        }

        @Override
        public String summary() {
            return "输出环境诊断信息（排查问题时先看它）";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            CliOptions o = ctx.options();
            CliConfigResolver.ProjectConfig project = ctx.project();

            out.println(out.theme().bold("环境"));
            row(out, "运行目录", System.getProperty("user.dir"));
            row(out, "框架根", String.valueOf(ctx.services().dirs().frameworkRoot()));
            row(out, "JVM", System.getProperty("java.version") + " @ " + System.getProperty("java.vendor"));
            row(out, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            // 输出编码如实报告「我们实际用的」（恒 UTF-8），并附上 JVM 默认与启动值供对照：
            // 读启动值会给出 GBK 这种「看起来是问题、其实早已被我们替换掉」的误导信息。
            row(out, "字符集", "输出=" + OutputSink.outputCharset().name() + "（强制）"
                    + " / JVM默认=" + java.nio.charset.Charset.defaultCharset().name()
                    + " / 启动stdout=" + System.getProperty("stdout.encoding", "(未设置)"));
            row(out, "终端", out.theme().enabled() ? "ANSI（交互式）" : "纯文本（非 TTY 或 --plain）");

            out.println(out.theme().bold("会话"));
            row(out, "当前会话", ctx.holder().sessionId() + "（" + ctx.holder().shortId() + "）");
            row(out, "工作空间", String.valueOf(ctx.services().workspaces()
                    .getWorkspace(ctx.holder().workspaceId())
                    .map(WorkspaceResolver::describe).orElse("(已失效)")));
            int sessionCount = ctx.services().sessions().list(ctx.holder().userId()).size();
            row(out, "会话总数", String.valueOf(sessionCount));

            out.println(out.theme().bold("配置来源（命令行 > 环境变量 > 项目配置 > 默认）"));
            row(out, "model", describe(ctx, o.model, "model"));
            row(out, "outputFormat", describe(ctx, o.outputFormat, "outputFormat"));
            row(out, "workspace", describe(ctx, o.workspace, "workspace"));
            row(out, "maxTurns", describe(ctx, o.maxTurns, "maxTurns"));
            row(out, "maxBudget", describe(ctx, o.maxBudget, "maxBudget"));

            out.println(out.theme().bold("项目本地配置"));
            if (project.enabled()) {
                row(out, "文件", String.valueOf(project.source()));
                row(out, "已生效键", project.values().isEmpty() ? "(无)" : String.valueOf(project.values().keySet()));
            } else if (!project.rejectedKeys().isEmpty()) {
                row(out, "状态", out.theme().red("已拒绝加载"));
                row(out, "原因", "含禁止键 " + project.rejectedKeys());
                row(out, "说明", "密钥/权限/路径根类配置不允许写进可能被提交的文件；详见 /help");
            } else {
                row(out, "文件", "未找到 " + CliConfigResolver.PROJECT_CONFIG_RELATIVE);
            }

            out.println(out.theme().bold("退出码"));
            for (int code : new int[]{CliExitCode.SUCCESS, CliExitCode.FAILURE, CliExitCode.USAGE,
                    CliExitCode.PERMISSION_DENIED, CliExitCode.BUDGET_EXHAUSTED, CliExitCode.PENDING_UNRESOLVED}) {
                out.println("  " + code + "  " + CliExitCode.describe(code));
            }
            return false;
        }

        private static String describe(CommandContext ctx, String flag, String key) {
            String source = ctx.services().configResolver().describeSource(flag, key, ctx.project());
            String value = ctx.services().configResolver().resolve(flag, key, ctx.project()).orElse("(默认)");
            return value + "   来源：" + source;
        }

        private static void row(OutputSink out, String label, String value) {
            out.println("  " + label + " ".repeat(Math.max(1, 12 - label.length())) + value);
        }
    }

    /** {@code /clear} —— 清屏（纯文本环境给出等效提示）。 */
    private static final class ClearCommand implements SlashCommand {

        @Override
        public String name() {
            return "clear";
        }

        @Override
        public String summary() {
            return "清屏";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            if (out.theme().enabled()) {
                out.print("\u001B[2J\u001B[H");
                out.flush();
            } else {
                out.println(out.theme().dim("（当前为纯文本输出，无法清屏）"));
            }
            return false;
        }
    }
}
