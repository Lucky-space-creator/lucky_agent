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
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.workspace.api.dto.Workspace;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
        r.register(new CompactCommand());
        r.register(new UndoCommand());
        r.register(new SafetyCommand());
        r.register(new ReloadCommand());
        r.register(new DoctorCommand());
        r.register(new ClearCommand());
        r.register(new McpCommand());
        r.register(new SkillsCommand());
        return r;
    }

    /**
     * 命令主题分组（用于 {@code /} 补全菜单的候选分组）。
     *
     * <p>集中在一处维护：命令类自身不必知道自己在 {@code /help} / 菜单里归哪一类，
     * 新增命令时只在这里补一行即可，避免为分组给 14 个命令类各加一个方法。</p>
     */
    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("help", "基础"), Map.entry("exit", "基础"), Map.entry("clear", "基础"),
            Map.entry("new", "会话"), Map.entry("sessions", "会话"), Map.entry("resume", "会话"),
            Map.entry("history", "会话"), Map.entry("compact", "会话"), Map.entry("undo", "会话"),
            Map.entry("workspace", "环境"), Map.entry("doctor", "环境"), Map.entry("reload", "环境"),
            Map.entry("safety", "环境"), Map.entry("tokens", "环境"),
            Map.entry("mcp", "扩展"), Map.entry("skills", "扩展"));

    /** 返回命令的主题分组（未知命令归「其他」）。 */
    public String groupOf(String name) {
        return name == null ? "其他" : GROUPS.getOrDefault(name.toLowerCase(Locale.ROOT), "其他");
    }

    /**
     * 从指定目录加载自定义命令（{@code commands/*.md}）。
     *
     * <p>约定：文件名（去扩展名）即命令名，文件首行非空文本作为 {@code /help} 摘要，
     * 全文作为提示词模板；调用 {@code /name} 即把正文作为一轮提示词提交给 Agent。
     * 内置命令同名时不被覆盖；扫描失败（目录不存在/无法读取）静默跳过。</p>
     *
     * <p><b>为何此处直接用 {@code Files}</b>：读取的是框架/项目自带的命令模板
     * （{@code <cwd>/commands}、{@code <frameworkRoot>/commands}），属框架配置而非用户工作区产物，
     * 不构成「绕过执行臂访问工作区文件」——决策 D2 的硬边界只约束工作区内的用户文件操作。</p>
     */
    public void registerCustomCommands(Path... dirs) {
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName() != null
                                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                                String name = p.getFileName().toString()
                                        .replaceAll("(?i)\\.md$", "").toLowerCase(Locale.ROOT);
                                if (name.isEmpty() || byName.containsKey(name)) {
                                    return; // 空名或不覆盖内置命令
                                }
                                StringBuilder body = new StringBuilder();
                                String summary = name;
                                boolean first = true;
                                for (String ln : lines) {
                                    if (first && !ln.isBlank()) {
                                        summary = ln.trim();
                                        first = false;
                                    }
                                    body.append(ln).append("\n");
                                }
                                register(new CustomCommand(name, summary, body.toString().strip()));
                            } catch (IOException ignore) {
                                // 跳过无法读取的文件
                            }
                        });
            } catch (IOException ignore) {
                // 跳过无法列举的目录
            }
        }
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
            out.println("  " + pad("!<命令>", 26)
                    + "直接交给执行臂执行，不经模型（仍受权限与边界校验）");
            out.println("  " + pad("", 26)
                    + out.theme().dim("↑ 前提：当前工作空间权限档须为「全部权限」，否则命令执行会被拒（用 /ws 查看）"));
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
            String currentWs = ctx.holder().workspaceId();
            String metaWs = meta.workspaceId() == null || meta.workspaceId().isBlank() ? null : meta.workspaceId();
            String ws = metaWs == null ? currentWs : metaWs;
            if (metaWs != null && !metaWs.equals(currentWs)) {
                // 会话绑定工作空间可能已被删除：存在则提示切换，不存在则回退当前空间并说明，
                // 避免把内核指向一个不存在的空间导致后续文件操作全部失败
                var bound = ctx.services().workspaces().getWorkspace(metaWs);
                if (bound.isPresent()) {
                    out.println(out.theme().dim("已切换到该会话绑定的工作空间：" + bound.get().name()));
                } else {
                    out.println(out.theme().yellow("该会话绑定的工作空间已不存在，本次沿用当前工作空间（历史仍可查看）。"));
                    ws = currentWs;
                }
            }
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
            return "/workspace [序号|ID|名称|路径]";
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
                out.println(out.theme().dim("切换：/workspace <序号|ID|名称|路径>（会给新工作空间开一个新会话）"));
                return false;
            }

            // 支持序号：直接复用上面列表的编号，用户不必复制长 ID（此前只认 ID/名称/路径，
            // 与列表展示的序号对不上 —— 照着提示敲「/workspace 2」必然 NOT_FOUND）。
            Optional<Workspace> byIndex =
                    WorkspaceResolver.byIndex(workspaces.listWorkspaces(), args.get(0));
            if (byIndex.isPresent()) {
                switchTo(ctx, byIndex.get());
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
            return "查看会话累计 token / 工具 / 缓存用量与上下文窗口占用";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            Map<String, Object> snap;
            try {
                // MetricsCollector#snapshot 是会话级累计量（每次模型调用增量累加），
                // 对未知会话返回「空指标」而不是 null，故不能用 null 判空，按「有没有真实调用」判定。
                snap = ctx.services().metrics().snapshot(ctx.holder().sessionId()).snapshot();
            } catch (Exception e) {
                log.debug("读取会话指标失败：{}", e.getMessage());
                snap = null;
            }
            if (snap == null || !hasActivity(snap)) {
                out.println(out.theme().dim("当前会话还没有运行记录。"));
                return false;
            }
            out.println(out.theme().bold("会话累计用量"));
            print(out, "token", snap.get("tokenUsed") + "（输入 " + snap.get("inputTokens")
                    + " / 输出 " + snap.get("outputTokens") + "）");
            print(out, "模型调用", snap.get("modelCalls"));
            print(out, "工具", "成功 " + snap.get("toolOk") + " / 失败 " + snap.get("toolFail"));
            print(out, "缓存", "命中 " + snap.get("cacheHits") + " / 未命中 " + snap.get("cacheMisses")
                    + "（命中率 " + snap.get("cacheHitRate") + "）");
            print(out, "Skill/MCP", snap.get("skillInvokes") + " / " + snap.get("mcpInvokes"));
            print(out, "错误", snap.get("errors"));
            print(out, "最近模型", snap.get("lastModel"));
            Object window = snap.get("contextWindow");
            if (window instanceof Number w && w.longValue() > 0) {
                double usage = snap.get("windowUsage") instanceof Number u ? u.doubleValue() : 0d;
                print(out, "上下文窗口", w.longValue() + " tokens"
                        + "（已占用 " + String.format("%.1f%%", usage * 100) + "）");
            }
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
            row(out, "plain", describe(ctx, o.plain ? "true" : null, "plain"));
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

    /** {@code /mcp} —— 列出已配置的 MCP 服务与连接状态（只读，不触发连接）。 */
    private static final class McpCommand implements SlashCommand {
        @Override
        public String name() {
            return "mcp";
        }

        @Override
        public String summary() {
            return "列出已配置的 MCP 服务与连接状态";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            if (ctx.services().mcp() == null) {
                out.println(out.theme().yellow("MCP 能力未装配，无法列出。"));
                return false;
            }
            List<McpServerDef> all = ctx.services().mcp().list();
            if (all.isEmpty()) {
                out.println(out.theme().dim("尚未配置任何 MCP 服务（可在 Web 端「MCP」中添加）。"));
                return false;
            }
            out.println(out.theme().bold("MCP 服务（共 " + all.size() + " 个）"));
            McpConnector connector = ctx.services().mcpConnector();
            for (McpServerDef def : all) {
                String mark = def.enabled() ? out.theme().green("●") : out.theme().dim("○");
                String enabled = def.enabled() ? out.theme().green("启用") : out.theme().dim("停用");
                String status = connector == null ? "" : "  " + statusLabel(out, connector, def);
                out.println("  " + mark + " " + out.theme().bold(def.id())
                        + "  " + (def.name() == null ? "" : def.name())
                        + "  [" + def.type() + "]  " + enabled + status);
                if (def.description() != null && !def.description().isBlank()) {
                    out.println(out.theme().dim("      " + def.description()));
                }
            }
            out.println(out.theme().dim("  只读展示；服务在首次实际调用其工具时才会建立连接。"));
            return false;
        }

        /** 连接状态标签；仅在已连接时附带工具数，避免"看一眼状态"却把服务拉起来。 */
        private static String statusLabel(OutputSink out, McpConnector connector, McpServerDef def) {
            String st = connector.status(def.id());
            String label = switch (st == null ? McpConnector.STATUS_STOPPED : st) {
                case McpConnector.STATUS_RUNNING -> out.theme().green("已连接");
                case McpConnector.STATUS_ERROR -> out.theme().red("连接失败");
                default -> out.theme().dim("未连接");
            };
            if (connector.isConnected(def.id())) {
                label += out.theme().dim("（工具 " + connector.listTools(def.id()).size() + "）");
            }
            return label;
        }
    }

    /** {@code /skills} —— 列出可用技能与启用状态（只读）。 */
    private static final class SkillsCommand implements SlashCommand {
        @Override
        public String name() {
            return "skills";
        }

        @Override
        public List<String> aliases() {
            return List.of("skill");
        }

        @Override
        public String summary() {
            return "列出可用技能与启用状态";
        }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            if (ctx.services().skills() == null) {
                out.println(out.theme().yellow("技能能力未装配，无法列出。"));
                return false;
            }
            List<SkillDef> all = ctx.services().skills().list();
            if (all.isEmpty()) {
                out.println(out.theme().dim("尚未安装任何技能。"));
                return false;
            }
            long enabled = all.stream().filter(SkillDef::enabled).count();
            out.println(out.theme().bold("技能（共 " + all.size() + " 个，启用 " + enabled + "）"));
            for (SkillDef s : all) {
                String mark = s.enabled() ? out.theme().green("●") : out.theme().dim("○");
                String type = SkillDef.TYPE_SUBAGENT.equals(s.type()) ? "子代理" : "工具";
                String source = SkillDef.SOURCE_PLATFORM.equals(s.source()) ? "预置" : "自定义";
                String line = "  " + mark + " " + out.theme().bold(s.id())
                        + "  " + (s.name() == null ? "" : s.name())
                        + "  [" + type + " · " + source + "]"
                        + (s.hasMissingDeps() ? out.theme().yellow("  缺依赖 " + s.missingDeps()) : "");
                out.println(line);
                if (s.description() != null && !s.description().isBlank()) {
                    out.println(out.theme().dim("      " + s.description()));
                }
            }
            out.println(out.theme().dim("  技能按语义匹配按需注入；本命令仅查看清单与启用状态。"));
            return false;
        }
    }

    /** {@code /compact} —— 手动压缩当前会话上下文（P2-2）。 */
    private static final class CompactCommand implements SlashCommand {
        @Override
        public String name() { return "compact"; }
        @Override
        public String summary() { return "手动压缩当前会话上下文（释放 token 占用）"; }
        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            try {
                Map<String, Object> r = ctx.services().conversations().compact(ctx.holder().ref()).block();
                if (r == null) {
                    out.println(out.theme().red("压缩失败：内核未返回结果。"));
                    return false;
                }
                if (Boolean.TRUE.equals(r.get("busy"))) {
                    out.println(out.theme().yellow("会话正在运行中，无法压缩，请等待本轮结束后再试。"));
                    return false;
                }
                int before = toInt(r.get("before"));
                int after = toInt(r.get("after"));
                if (!Boolean.TRUE.equals(r.get("changed"))) {
                    out.println(out.theme().dim("上下文无需压缩（当前 " + before + " 条消息）。"));
                } else {
                    out.println(out.theme().green("已压缩上下文：" + before + " → " + after + " 条消息。"));
                }
            } catch (Exception e) {
                out.println(out.theme().red("压缩失败：" + e.getMessage()));
            }
            return false;
        }
    }

    /** {@code /undo} —— 撤销上一轮（双回滚：文件 + 对话）。 */
    private static final class UndoCommand implements SlashCommand {
        @Override
        public String name() { return "undo"; }
        @Override
        public String summary() { return "撤销上一轮：恢复文件检查点并截断对话"; }
        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            try {
                Map<String, Object> r = ctx.services().conversations().undoLastTurn(ctx.holder().ref()).block();
                if (r == null) {
                    out.println(out.theme().red("撤销失败：内核未返回结果。"));
                    return false;
                }
                if (Boolean.TRUE.equals(r.get("busy"))) {
                    out.println(out.theme().yellow("会话正在运行中，无法撤销，请等待本轮结束后再试。"));
                    return false;
                }
                int files = toInt(r.get("filesRestored"));
                int msgs = toInt(r.get("messagesRemoved"));
                if (files == 0 && msgs == 0) {
                    out.println(out.theme().dim("没有可撤销的内容（当前会话为空或尚无完整回合）。"));
                } else {
                    out.println(out.theme().green("已撤销上一轮：移除 " + msgs
                            + " 条消息，恢复 " + files + " 个文件检查点。"));
                }
            } catch (Exception e) {
                out.println(out.theme().red("撤销失败：" + e.getMessage()));
            }
            return false;
        }
    }

    /** {@code /safety} —— 展示两轴安全模型与当前生效策略（P2-6）。 */
    private static final class SafetyCommand implements SlashCommand {
        @Override
        public String name() { return "safety"; }
        @Override
        public List<String> aliases() { return List.of("sec"); }
        @Override
        public String summary() { return "查看两轴安全模型（沙箱 × 审批）与当前生效策略"; }
        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            out.println(out.theme().bold("两轴安全模型（本地优先 · 零托管）"));
            out.println();
            out.println(out.theme().bold("X 轴 · 沙箱（隔离与硬边界）"));
            out.println("  · 本机优先：会话/记忆/文件只在本机，平台不接触用户内容");
            out.println("  · 执行臂硬边界：所有文件/命令操作经 realpath 校验，越界即拒");
            out.println("  · 不托管模型：模型由用户自带 Key/本地模型接入，框架只编排");
            out.println("  · 不联网服务：纯本机单体，不要求公网 IP / 云服务器");
            out.println();
            out.println(out.theme().bold("Y 轴 · 审批（deny > ask > allow）"));
            out.println("  · 规则链：deny 优先，其次 ask 询问，最后 allow 放行");
            out.println("  · 权限档：由工作空间权限级别 + 规则链共同决定，LLM 软约束仅作引导");
            out.println("  · 自动代答：--auto-approve 仅在「需询问时」代答允许一次，不绕过硬边界");
            out.println("  · 条件选择：--auto-pick 仅自动选「推荐项」，与授权代答相互独立");
            out.println("  · 危险操作：删除/覆盖/外发等被识别并要求显式确认");
            out.println();
            String wsId = ctx.holder().workspaceId();
            Optional<Workspace> ws = ctx.services().workspaces().getWorkspace(wsId);
            String perm = ws.flatMap(w -> Optional.ofNullable(w.permissionLevel())).map(p -> p.getLabel()).orElse("(默认)");
            String auto = ctx.options().autoApprove
                    ? out.theme().yellow("开启（仅代答一次）") : out.theme().dim("关闭");
            String autoPick = ctx.options().autoPick
                    ? out.theme().yellow("开启") : out.theme().dim("关闭");
            out.println(out.theme().bold("当前会话生效策略"));
            out.println("  · 工作空间：" + (ws.isPresent() ? ws.get().name() : wsId));
            out.println("  · 权限档：" + perm);
            out.println("  · 自动代答：" + auto + "    自动选选项：" + autoPick);
            out.println(out.theme().dim("  执行臂硬边界与规则链不可被 CLI 参数绕过（决策 D2）。"));
            return false;
        }
    }

    /**
     * {@code /reload} —— 会话内手动重载项目本地配置（P1-7 / 决策 5）。
     *
     * <p>对齐 {@code LUCKY.md}「改完保存即生效」的既有语义：项目配置默认启动时读取一次，
     * 编辑 {@code <cwd>/.lucky/settings.json} 后执行 {@code /reload} 即可在会话内生效，
     * 无需重启 CLI。重载同样走键白名单校验（禁止键 → 整文件拒绝）。</p>
     */
    private static final class ReloadCommand implements SlashCommand {
        @Override
        public String name() { return "reload"; }
        @Override
        public String summary() { return "重新读取项目本地配置（<cwd>/.lucky/settings.json），保存即生效"; }

        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            OutputSink out = ctx.out();
            Path cwd = ctx.options().cwd != null && !ctx.options().cwd.isBlank()
                    ? Path.of(ctx.options().cwd).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir"));
            CliConfigResolver.ProjectConfig p = ctx.services().configResolver()
                    .loadProjectConfig(cwd, ctx.services().dirs().frameworkRoot());
            ctx.reloadProject(p);
            if (p.enabled()) {
                Path src = p.source();
                out.println(out.theme().green("已重载项目配置：") + src);
                if (p.values().isEmpty()) {
                    out.println(out.theme().dim("  （白名单内无有效键）"));
                } else {
                    p.values().forEach((k, v) -> out.println("  " + k + " = " + v));
                }
            } else if (!p.rejectedKeys().isEmpty()) {
                out.println(out.theme().red("项目配置被拒绝加载：含禁止键 " + p.rejectedKeys()));
                out.println(out.theme().dim("  密钥/权限/路径根类配置不允许写进可能被提交的文件；详见 /doctor。"));
            } else {
                out.println(out.theme().dim("未找到项目配置：" + cwd.resolve(CliConfigResolver.PROJECT_CONFIG_RELATIVE)));
            }
            return false;
        }
    }

    /** 自定义命令（commands/*.md）：把文件正文作为一轮提示词提交。 */
    private static final class CustomCommand implements SlashCommand {
        private final String name;
        private final String summary;
        private final String body;

        CustomCommand(String name, String summary, String body) {
            this.name = name;
            this.summary = summary;
            this.body = body;
        }

        @Override
        public String name() { return name; }
        @Override
        public String summary() { return summary; }
        @Override
        public boolean run(CommandContext ctx, List<String> args) {
            if (body != null && !body.isBlank()) {
                ctx.runPrompt(body);
            }
            return false;
        }
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
