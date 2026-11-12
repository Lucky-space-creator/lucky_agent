package com.lucky.agent.cli;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.CliChannel;
import com.lucky.agent.cli.channel.EventRenderer;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.command.CliServices;
import com.lucky.agent.cli.command.CommandContext;
import com.lucky.agent.cli.command.SlashCommand;
import com.lucky.agent.cli.command.SlashCommandRegistry;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.headless.OutputFormatter;
import com.lucky.agent.cli.headless.PrintModeRunner;
import com.lucky.agent.cli.input.FileReference;
import com.lucky.agent.cli.repl.ReplLoop;
import com.lucky.agent.cli.session.CliSessionService;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.cli.term.LineEditor;
import com.lucky.agent.cli.term.TerminalSession;
import com.lucky.agent.cli.term.WorkspacePathCompleter;
import com.lucky.agent.cli.workspace.WorkspaceResolver;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.repository.SessionRepository;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.skill.service.SkillRegistry;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.Candidate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CLI 装配中枢：把「参数 → 工作空间 → 会话 → 终端 → 运行形态」串起来。
 *
 * <p>本类是唯一知道全部依赖关系的地方，其余类只依赖自己真正需要的东西。</p>
 *
 * <p><b>三种运行形态</b></p>
 * <ol>
 *   <li>{@code --list-sessions}：只列会话即退出（不需要终端）</li>
 *   <li>有提示词（{@code -p} 或位置参数）：headless，跑完用退出码表态</li>
 *   <li>无提示词：交互式 REPL</li>
 * </ol>
 *
 * <p><b>headless 下刻意不建立 JLine 终端</b>：JLine 会接管并读取 stdin，而 headless 的提示词
 * 可能来自管道或是纯 stdout 消费场景。建立终端会与「从 stdin 读数据」打架，
 * 还可能把行编辑控制序列写进 stdout 污染管道输出。</p>
 *
 * <p><b>退出码语义</b>见 {@link CliExitCode}；本类只负责把「无法运行」的情形映射为
 * {@link CliExitCode#USAGE}（用法/环境错误），运行结果由 {@link TurnOutcome} 判定。</p>
 */
@Slf4j
@Component
public class CliRunner {

    /**
     * 本机用户标识。
     *
     * <p>必须与 Web 端一致（前端 {@code LOCAL_USER_ID = 'local-user'}）：会话列表、记忆目录、
     * 会话落盘都按 userId 分片。若 CLI 用别的值，CLI 与浏览器就是两个不同的人，
     * {@code -r} 也就无法恢复浏览器里创建的会话。</p>
     */
    public static final String LOCAL_USER = "local-user";

    /**
     * 工作空间歧义时最多询问次数。
     *
     * <p>上限而非常驻循环的理由：本方法也可能被脚本化场景触发（例如在自动化里喂错输入），
     * 无上限重问会让进程静默卡住；有上限则最坏情况只是停止追问、回落到内置默认工作空间继续。</p>
     */
    private static final int MAX_WORKSPACE_PROMPT_ATTEMPTS = 3;

    private final CliChannel channel;
    private final ConversationManager conversations;
    private final ConversationStateManager stateManager;
    private final CliSessionService sessions;
    private final WorkspaceConfig workspaces;
    private final WorkspaceDirs dirs;
    private final MetricsCollector metrics;
    private final FileService files;
    private final PermissionService permission;
    private final McpRegistry mcpRegistry;
    private final McpConnector mcpConnector;
    private final SkillRegistry skillRegistry;
    private final CliConfigResolver configResolver = new CliConfigResolver();

    public CliRunner(CliChannel channel, ConversationManager conversations,
                     ConversationStateManager stateManager, CliSessionService sessions,
                     WorkspaceConfig workspaces, WorkspaceDirs dirs, MetricsCollector metrics,
                     FileService files, PermissionService permission,
                     McpRegistry mcpRegistry, McpConnector mcpConnector, SkillRegistry skillRegistry) {
        this.channel = channel;
        this.conversations = conversations;
        this.stateManager = stateManager;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.dirs = dirs;
        this.metrics = metrics;
        this.files = files;
        this.permission = permission;
        this.mcpRegistry = mcpRegistry;
        this.mcpConnector = mcpConnector;
        this.skillRegistry = skillRegistry;
    }

    /** 运行并返回进程退出码。 */
    public int run(CliOptions options) {
        boolean headless = options.headless();

        // 查找起点：--cwd 可覆盖，默认取进程 CWD（决策 5 §5.3）
        Path startDir = options.cwd != null && !options.cwd.isBlank()
                ? Path.of(options.cwd).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir"));
        // 项目本地配置必须先于终端建立：plain 可能是项目配置项，需参与「是否建终端」的判定
        CliConfigResolver.ProjectConfig project = configResolver.loadProjectConfig(
                startDir, dirs.frameworkRoot());
        // 输出形态同样走三级解析（命令行 > 环境变量 > 项目本地）；
        // 命令行未给出时 --output-format 为 null，故不会被缺省值顶掉项目配置层
        String resolvedOutputFormat = configResolver
                .resolve(options.outputFormat, "outputFormat", project).orElse(null);
        boolean plain = options.plain || configResolver
                .resolve(options.plain ? "true" : null, "plain", project)
                .map(Boolean::parseBoolean).orElse(false);

        // 输出通道：headless 双通道（正文 stdout、进度 stderr）；交互式单通道
        TerminalSession terminal = null;
        OutputSink out;
        OutputSink errSink;
        if (headless) {
            out = OutputSink.of(OutputSink.utf8Stdout(), Theme.plain());
            errSink = OutputSink.of(OutputSink.utf8Stderr(), Theme.plain());
        } else {
            // 交互式历史落 <frameworkRoot>/agent/cli-history（D4 目录规范，路径唯一定义处见 LineEditor.historyFile）
            terminal = TerminalSession.open(plain, LineEditor.historyFile(dirs.frameworkRoot()));
            out = terminal.sink();
            errSink = out;
        }

        try {
            warnRejectedProjectConfig(project, errSink);
            warnUnknownOutputFormat(resolvedOutputFormat, errSink);
            warnInvalidRunExtra(options, project, errSink);

            // --list-sessions 是纯只读、且只与会话相关的能力：会话并不按工作空间隔离
            // （即使带了 -w，列出的仍是全部会话），因此必须排在「工作空间解析」之前。
            // 否则多工作空间环境下它会因歧义直接失败——而启动脚本的用法提示里正是拿
            // `--list-sessions` 当入门命令（缺陷 D8）。
            if (options.listSessions) {
                printSessions(out);
                return CliExitCode.SUCCESS;
            }

            // 非交互（非 TTY / --plain / 管道）下不传 editor：不主动从 stdin 读「选择工作空间」，
            // 以免在管道场景把数据行当成序号吃掉。此时由 pickWorkspace 回落到内置默认工作空间。
            // 交互式 TTY 才把 editor 传给 pickWorkspace 让其询问。
            LineEditor workspaceEditor =
                    (terminal == null || !terminal.interactive()) ? null : terminal.editor();
            // 工作空间选择器同样走三级解析：命令行 -w > 环境变量 > 项目本地配置（决策 5）
            String workspaceSelector = configResolver.resolve(options.workspace, "workspace", project)
                    .orElse(null);
            Optional<Workspace> picked = pickWorkspace(workspaceSelector, workspaceEditor, out);
            if (picked.isEmpty()) {
                return CliExitCode.USAGE;
            }
            Workspace workspace = picked.get();

            // REPL 不再要求 TTY：非 TTY（IDE 运行控制台、管道、CI）下 JLine 退化为纯文本编辑器，
            // 逐行读 stdin，读到 EOF / exit 即正常退出，不会挂起 —— 此前一律拒绝，导致
            // 「IDE 运行控制台无法使用 CLI」这一高频场景直接不可用（真缺陷）。
            // 一次性脚本仍建议用 -p：它不做交互、退出码语义更明确。
            if (!headless && terminal == null) {
                errSink.println(errSink.theme().red("无法建立终端输入通道，无法进入会话。"));
                return CliExitCode.USAGE;
            }

            SessionRef ref;
            try {
                // 传「是否显式指定过工作空间」而不是只看命令行 -w：
                // 项目配置/环境变量同样是一种明确指定，只看 options.workspace 会漏提示绑定冲突
                ref = resolveSession(options, workspace, workspaceSelector != null, out);
            } catch (CliUsageException e) {
                errSink.println(errSink.theme().red(e.getMessage()));
                return CliExitCode.USAGE;
            }
            SessionHolder holder = new SessionHolder(ref);

            CliServices services = new CliServices(sessions, conversations, stateManager, workspaces,
                    dirs, metrics, files, permission, configResolver,
                    mcpRegistry, mcpConnector, skillRegistry);
            LineEditor editor = terminal == null ? null : terminal.editor();
            ApprovalHandler approval = new ApprovalHandler(
                    headless ? errSink : out, headless ? null : editor,
                    permission, workspaces, options.autoApprove);
            // 仅在「交互式且真有 ANSI 终端」时监听 Ctrl+C：headless 下 stdin 可能承载数据，
            // 抢读字节会破坏输入
            CliTurnExecutor executor = new CliTurnExecutor(channel, !headless && out.theme().enabled());

            if (headless) {
                OutputFormatter formatter = new OutputFormatter(
                        OutputFormatter.Mode.parse(CliOptions.normalizeOutputFormat(resolvedOutputFormat)),
                        out, errSink, options.showThinking);
                channel.attachRenderer(formatter);
                CliTurnLoop turns = new CliTurnLoop(executor, approval, errSink, null);
                Map<String, Object> runExtra = configResolver.effectiveRunExtra(
                        options.model, options.maxTurns, options.maxBudget, project);
                return new PrintModeRunner(options, turns, formatter, errSink, holder, runExtra)
                        .run(options.promptText());
            }

            channel.attachRenderer(new EventRenderer(out, options.showThinking));
            SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
            // 自定义命令：<cwd>/commands 与 <frameworkRoot>/commands 下的 *.md（文件名即命令名）
            registry.registerCustomCommands(
                    startDir.resolve("commands"),
                    dirs.frameworkRoot().resolve("commands"));
            CommandContext ctx = new CommandContext(out, services, options, project, holder, () -> {
            });
            installCompleter(editor, registry, holder);
            ReplLoop loop = new ReplLoop(out, editor, options, ctx, registry, executor, approval, holder);
            return loop.run();
        } finally {
            if (terminal != null) {
                terminal.close();
            }
        }
    }

    /**
     * 注入行补全：命令菜单（{@code /}）+ 工作空间文件路径补全（{@code @}）。
     *
     * <p><b>两条互补的补全</b>，按当前词的形态分流（与 {@code ReplLoop} 的前缀判定同源）：</p>
     * <ol>
     *   <li>行首且以 {@code /} 开头 → 命令菜单：带 <b>summary 描述</b>与主题分组，
     *       继续打字即过滤。JLine 的 {@link Candidate} 原生支持 {@code descr}，此前只 {@code new Candidate(c)}
     *       未填，等于把「可发现性」白白丢掉。</li>
     *   <li>行首且以 {@code @} 开头 → 工作空间文件/目录补全，交给 {@link WorkspacePathCompleter}
     *       （它经执行臂列目录、异步预取 + 缓存，见该类说明）。</li>
     * </ol>
     *
     * <p>只认「行首」是刻意的：{@code ReplLoop} 也只把行首的 {@code /} / {@code @} 当命令/引用，
     * 补全与执行必须同一套判定，否则会出现「补全给出了、回车却不生效」的错觉。</p>
     */
    private void installCompleter(LineEditor editor, SlashCommandRegistry registry, SessionHolder holder) {
        if (editor == null) {
            return;
        }
        WorkspacePathCompleter pathCompleter = new WorkspacePathCompleter(files, holder);
        editor.setCompleter((reader, line, candidates) -> {
            String word = line == null || line.word() == null ? "" : line.word();
            // 仅行首词参与补全：/ 命令与 @ 引用都只在行首生效（见 ReplLoop 的分发顺序）
            if (line != null && line.wordIndex() != 0) {
                return;
            }
            if (word.startsWith("/")) {
                addCommandCandidates(registry, word, candidates);
            } else if (word.startsWith(String.valueOf(FileReference.PREFIX))) {
                pathCompleter.complete(word, candidates);
            }
        });
    }

    /** 构造带描述与分组的命令候选（供 {@code /} 菜单显示）。 */
    private static void addCommandCandidates(SlashCommandRegistry registry, String slashWord,
                                             List<Candidate> candidates) {
        String prefix = slashWord.toLowerCase(Locale.ROOT);
        for (SlashCommand cmd : registry.commands()) {
            String name = "/" + cmd.name();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String descr = cmd.summary();
            if (!cmd.aliases().isEmpty()) {
                descr = descr + "（别名 /" + String.join(" /", cmd.aliases()) + "）";
            }
            // value=插入文本、displ=菜单显示、group=主题分组、descr=右侧说明
            candidates.add(new Candidate(name, name, registry.groupOf(cmd.name()), descr, null, null, true));
        }
    }

    // ------------------------------------------------------------------ 工作空间

    /**
     * 选定唯一工作空间。
     *
     * <p>多候选时：交互式（TTY）问用户选哪个；无法询问（非 TTY / 用户放弃）时<b>回落到内置默认
     * 工作空间</b>并大声回显 —— 「没选也必须有一个默认落点」。候选里有多个用户自建空间时随便挑一个
     * 才是真正的「猜」（会把 Agent 指向用户没预期的目录）；内置默认是框架自己的目录、权限可预期，
     * 属既定落点。</p>
     *
     * <p>仅当两种情况才以用法错误退出：用户<b>显式指定</b>了 {@code -w} 却无匹配（NOT_FOUND），
     * 或系统内确实没有任何工作空间（连内置都没有，EMPTY）。</p>
     */
    private Optional<Workspace> pickWorkspace(String selector, LineEditor editor, OutputSink out) {
        WorkspaceResolver.Result result = WorkspaceResolver.resolve(workspaces, selector);
        switch (result.status()) {
            case OK -> {
                return Optional.of(result.workspace());
            }
            case EMPTY -> {
                out.println(out.theme().red("系统内没有任何工作空间，无法运行。"));
                out.println(out.theme().dim("  请先在 Web 端「工作空间」中创建一个，或用 -w 指定一个存在的路径。"));
                return Optional.empty();
            }
            case NOT_FOUND -> {
                out.println(out.theme().red("未找到匹配的工作空间："
                        + (selector == null ? "(未指定)" : selector)));
                out.println(out.theme().dim("  用 -w <ID|名称|路径> 指定；不带 -w 会列出全部候选。"));
                return Optional.empty();
            }
            case AMBIGUOUS -> {
                List<Workspace> candidates = result.candidates();
                out.println(out.theme().yellow("存在多个可用工作空间，请选择（CLI 不代为猜测）："));
                for (int i = 0; i < candidates.size(); i++) {
                    out.println(String.format("  %d. %s", i + 1, WorkspaceResolver.describe(candidates.get(i))));
                }
                if (editor != null) {
                    Optional<Workspace> chosen = askWorkspace(candidates, editor, out);
                    if (chosen.isPresent()) {
                        return chosen;
                    }
                } else {
                    out.println(out.theme().dim("  当前为非交互环境，无法询问；也可用 -w 直接指定"
                            + "（支持 名称、前 8 位 ID 或完整 ID）。"));
                }
                // 未选择（非交互 / 用户放弃 / 连续输错）：回落到内置默认工作空间，
                // 而不是让 CLI 直接退出 —— 「没选也必须有一个默认落点」是可用性底线。
                return fallbackToBuiltin(candidates, out);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * 就候选列表询问用户（仅交互式 TTY 调用）。
     *
     * <p>循环重问：提示语说的是「请选择序号」，那就必须真的允许重试。此前输错一次即
     * return，用户只得到一句「请输入数字序号。」就被踢出进程（缺陷 D9）。</p>
     *
     * @return 用户选中的工作空间；用户放弃（回车）或连续多次输入无效时为空
     */
    private Optional<Workspace> askWorkspace(List<Workspace> candidates, LineEditor editor, OutputSink out) {
        for (int attempt = 1; attempt <= MAX_WORKSPACE_PROMPT_ATTEMPTS; attempt++) {
            String answer = editor.readLine("请选择序号（回车使用默认工作空间）: ");
            if (answer == null || answer.isBlank()) {
                return Optional.empty();
            }
            String trimmedAnswer = answer.trim();
            try {
                int idx = Integer.parseInt(trimmedAnswer);
                if (idx >= 1 && idx <= candidates.size()) {
                    return Optional.of(candidates.get(idx - 1));
                }
                out.println(out.theme().red("序号超出范围（可选 1-" + candidates.size() + "）。"));
            } catch (NumberFormatException e) {
                out.println(out.theme().red("请输入数字序号。"));
            }
            // 也允许直接按名称 / ID 指定：用户往往知道工作空间名而不愿数序号
            WorkspaceResolver.Result byName = WorkspaceResolver.resolve(workspaces, trimmedAnswer);
            if (byName.ok()) {
                return Optional.of(byName.workspace());
            }
        }
        out.println(out.theme().dim("连续 " + MAX_WORKSPACE_PROMPT_ATTEMPTS + " 次未提供有效序号。"));
        return Optional.empty();
    }

    /**
     * 兜底落点：内置默认工作空间（物理路径位于框架根内，即框架本机的 agent 目录所在地）。
     *
     * <p><b>为什么不是「猜」</b>：候选里有多个用户自建空间，随便挑一个确实等于猜（会把 Agent
     * 指向用户没预期的目录）。而内置默认空间是框架<b>自己</b>的目录、权限可预期、且下面马上
     * 大声回显并给出切换方式 —— 它是一个明确的既定落点，不是随机选择。</p>
     *
     * @return 内置默认工作空间；系统内无内置项时为空（此时才报用法错误退出）
     */
    private Optional<Workspace> fallbackToBuiltin(List<Workspace> candidates, OutputSink out) {
        Optional<Workspace> builtin = WorkspaceResolver.builtinDefault(workspaces);
        if (builtin.isPresent()) {
            Workspace w = builtin.get();
            out.println(out.theme().yellow("未选择工作空间，已使用内置默认工作空间："));
            out.println("  " + WorkspaceResolver.describe(w));
            out.println(out.theme().dim("  用 /workspace 可随时切换（切换会给目标空间开一个新会话）；"
                    + "用 -w 可在启动时直接指定。"));
            return builtin;
        }
        out.println(out.theme().red("没有内置默认工作空间，且未指定 -w，无法确定落点。"));
        out.println(out.theme().dim("  请用 -w <ID|名称|路径> 指定；候选之一："
                + WorkspaceResolver.describe(candidates.get(0))));
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 会话

    /** 会话定位失败（用法错误，映射为退出码 2）。 */
    private static final class CliUsageException extends RuntimeException {
        CliUsageException(String message) {
            super(message);
        }
    }

    private SessionRef resolveSession(CliOptions options, Workspace workspace,
                                      boolean workspaceExplicit, OutputSink out) {
        if (options.resume != null && !options.resume.isBlank()) {
            // -r 与 -c 同时给出时，此前会静默以 -r 为准，用户无从得知自己的 -c 被忽略
            if (options.continueLast) {
                out.println(out.theme().yellow("同时指定了 -r 与 -c，按 -r 指定的会话恢复（-c 已忽略）。"));
            }
            var found = sessions.resolve(options.resume, LOCAL_USER);
            if (found.isEmpty()) {
                String hint = sessions.isAmbiguous(options.resume, LOCAL_USER)
                        ? "选择器命中多个会话，请给出更长的 ID 前缀。"
                        : "未找到该会话（用 --list-sessions 查看序号与 ID）。";
                throw new CliUsageException("恢复会话失败：" + hint);
            }
            return resumed(found.get(), workspaceExplicit, workspace, out, "已恢复会话 ");
        }
        if (options.continueLast) {
            Optional<SessionRepository.SessionMeta> latest = sessions.latest(LOCAL_USER);
            if (latest.isPresent()) {
                return resumed(latest.get(), workspaceExplicit, workspace, out, "已继续最近会话 ");
            }
            out.println(out.theme().dim("没有可继续的历史会话，已为你开启新会话。"));
        }
        // 新会话：本地生成 ID，首条消息落盘时才写元数据 ——
        // 避免每次启动 CLI 都留下一个空会话把 /sessions 刷满
        return new SessionRef(UUID.randomUUID().toString(), LOCAL_USER, workspace.workspaceId());
    }

    /**
     * 组装「恢复/继续」的 SessionRef，并处理工作空间绑定冲突。
     *
     * @param workspaceExplicit 用户是否明确指定过工作空间（命令行 / 项目配置 / 环境变量任一来源）。
     *                          只有「明确指定过且与会话绑定不符」才提示，避免默认启动时刷屏。
     */
    private SessionRef resumed(SessionRepository.SessionMeta meta, boolean workspaceExplicit,
                               Workspace requested, OutputSink out, String prefix) {
        String boundWs = meta.workspaceId() == null || meta.workspaceId().isBlank()
                ? requested.workspaceId() : meta.workspaceId();
        if (workspaceExplicit && !boundWs.equals(requested.workspaceId())) {
            out.println(out.theme().yellow("注意：该会话已绑定到另一个工作空间，本次以会话自身的绑定为准"
                    + "（会话一旦有对话记录便不允许切换工作空间）。"));
        }
        out.println(out.theme().green(prefix) + out.theme().dim(shortId(meta.sessionId()))
                + (meta.title() == null || meta.title().isBlank()
                ? "" : "  " + out.theme().dim(meta.title())));
        return new SessionRef(meta.sessionId(), LOCAL_USER, boundWs);
    }

    private void printSessions(OutputSink out) {
        List<SessionRepository.SessionMeta> all = sessions.list(LOCAL_USER);
        if (all.isEmpty()) {
            out.println(out.theme().dim("还没有任何会话。"));
            return;
        }
        out.println(out.theme().bold("历史会话（共 " + all.size() + " 个，1 为最近）"));
        for (int i = 0; i < all.size(); i++) {
            out.println(sessions.format(all.get(i), i + 1));
        }
        out.println(out.theme().dim("用 lucky -r <序号> 恢复，或 lucky -c 继续最近一次。"));
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    // ------------------------------------------------------------------ 提示

    private void warnRejectedProjectConfig(CliConfigResolver.ProjectConfig project, OutputSink err) {
        if (project.rejectedKeys().isEmpty()) {
            return;
        }
        err.println(err.theme().red("项目本地配置已被拒绝加载：" + project.source()));
        err.println(err.theme().red("  含有安全红线字段：" + project.rejectedKeys()));
        err.println(err.theme().dim("  密钥 / 权限规则 / MCP 授权 / 路径根不允许写在可能被提交进仓库的文件里。"));
        err.println(err.theme().dim("  本次将忽略该文件，其余配置与默认值照常生效。"));
    }

    private void warnUnknownOutputFormat(String resolved, OutputSink err) {
        if (!CliOptions.outputFormatValid(resolved)) {
            err.println(err.theme().yellow("未知的输出格式「" + resolved + "」，已回落为 text。"));
        }
    }

    /**
     * 对「给了但不合法」的 run 级覆盖值告警（缺陷 D11）。
     *
     * <p>口径与 {@link #warnUnknownOutputFormat} 一致：<b>告警但继续</b>——用户多半只是笔误，
     * 回退默认值把这一轮跑完比直接拒绝更有用；要修的是「完全不反馈」这一点：
     * 以前 {@code --max-turns abc} 与不传参数在用户可见层面上毫无差别。
     * 输出走 {@code errSink}，与 headless 的进度同流，不污染 stdout 的「只有正文」契约。</p>
     */
    private void warnInvalidRunExtra(CliOptions options, CliConfigResolver.ProjectConfig project,
                                     OutputSink err) {
        configResolver.invalidRunExtra(options.maxTurns, options.maxBudget, project)
                .forEach((key, reason) -> err.println(err.theme().yellow(
                        "无效的 --" + key.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
                                + "：" + reason + "，已按默认值继续。")));
    }
}
