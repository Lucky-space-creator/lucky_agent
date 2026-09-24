package com.lucky.agent.cli;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.CliChannel;
import com.lucky.agent.cli.channel.EventRenderer;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.command.CliServices;
import com.lucky.agent.cli.command.CommandContext;
import com.lucky.agent.cli.command.SlashCommandRegistry;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.headless.OutputFormatter;
import com.lucky.agent.cli.headless.PrintModeRunner;
import com.lucky.agent.cli.repl.ReplLoop;
import com.lucky.agent.cli.session.CliSessionService;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.cli.term.LineEditor;
import com.lucky.agent.cli.term.TerminalSession;
import com.lucky.agent.cli.workspace.WorkspaceResolver;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.repository.SessionRepository;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.Candidate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
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

    private final CliChannel channel;
    private final ConversationManager conversations;
    private final ConversationStateManager stateManager;
    private final CliSessionService sessions;
    private final WorkspaceConfig workspaces;
    private final WorkspaceDirs dirs;
    private final MetricsCollector metrics;
    private final FileService files;
    private final PermissionService permission;
    private final CliConfigResolver configResolver = new CliConfigResolver();

    public CliRunner(CliChannel channel, ConversationManager conversations,
                     ConversationStateManager stateManager, CliSessionService sessions,
                     WorkspaceConfig workspaces, WorkspaceDirs dirs, MetricsCollector metrics,
                     FileService files, PermissionService permission) {
        this.channel = channel;
        this.conversations = conversations;
        this.stateManager = stateManager;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.dirs = dirs;
        this.metrics = metrics;
        this.files = files;
        this.permission = permission;
    }

    /** 运行并返回进程退出码。 */
    public int run(CliOptions options) {
        boolean headless = options.headless();

        // 输出通道：headless 双通道（正文 stdout、进度 stderr）；交互式单通道
        TerminalSession terminal = null;
        OutputSink out;
        OutputSink errSink;
        if (headless) {
            out = OutputSink.of(OutputSink.utf8Stdout(), Theme.plain());
            errSink = OutputSink.of(OutputSink.utf8Stderr(), Theme.plain());
        } else {
            terminal = TerminalSession.open(options.plain);
            out = terminal.sink();
            errSink = out;
        }

        try {
            CliConfigResolver.ProjectConfig project = configResolver.loadProjectConfig(
                    Path.of(System.getProperty("user.dir")), dirs.frameworkRoot());
            warnRejectedProjectConfig(project, errSink);
            warnUnknownOutputFormat(options, errSink);

            Optional<Workspace> picked = pickWorkspace(options, terminal == null ? null : terminal.editor(), out);
            if (picked.isEmpty()) {
                return CliExitCode.USAGE;
            }
            Workspace workspace = picked.get();

            if (options.listSessions) {
                printSessions(out);
                return CliExitCode.SUCCESS;
            }

            SessionRef ref;
            try {
                ref = resolveSession(options, workspace, out);
            } catch (CliUsageException e) {
                errSink.println(errSink.theme().red(e.getMessage()));
                return CliExitCode.USAGE;
            }
            SessionHolder holder = new SessionHolder(ref);

            CliServices services = new CliServices(sessions, conversations, stateManager, workspaces,
                    dirs, metrics, files, permission, configResolver);
            LineEditor editor = terminal == null ? null : terminal.editor();
            ApprovalHandler approval = new ApprovalHandler(
                    headless ? errSink : out, headless ? null : editor,
                    permission, workspaces, options.autoApprove);
            // 仅在「交互式且真有 ANSI 终端」时监听 Ctrl+C：headless 下 stdin 可能承载数据，
            // 抢读字节会破坏输入
            CliTurnExecutor executor = new CliTurnExecutor(channel, !headless && out.theme().enabled());

            if (headless) {
                OutputFormatter formatter = new OutputFormatter(
                        OutputFormatter.Mode.parse(options.outputFormat), out, errSink,
                        options.showThinking);
                channel.attachRenderer(formatter);
                CliTurnLoop turns = new CliTurnLoop(executor, approval, errSink, null);
                return new PrintModeRunner(options, turns, formatter, errSink, holder)
                        .run(options.promptText());
            }

            channel.attachRenderer(new EventRenderer(out, options.showThinking));
            SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
            CommandContext ctx = new CommandContext(out, services, options, project, holder, () -> {
            });
            installCompleter(editor, registry);
            ReplLoop loop = new ReplLoop(out, editor, options, ctx, registry, executor, approval, holder);
            return loop.run();
        } finally {
            if (terminal != null) {
                terminal.close();
            }
        }
    }

    /**
     * 注入行补全：只补全 slash 命令名。
     *
     * <p>刻意不补全文件路径：路径补全需要沿当前输入实时列目录，而列目录属于文件操作 ——
     * 在补全回调里做文件操作会绕过执行臂（决策 D2 要求所有文件操作经硬边界校验）。
     * 要引用文件请用 {@code @路径}，那条路径会正经走执行臂读取。</p>
     */
    private void installCompleter(LineEditor editor, SlashCommandRegistry registry) {
        if (editor == null) {
            return;
        }
        editor.setCompleter((reader, line, candidates) -> {
            String word = line == null ? "/" : line.word();
            registry.completions(word).forEach(c -> candidates.add(new Candidate(c)));
        });
    }

    // ------------------------------------------------------------------ 工作空间

    /**
     * 选定唯一工作空间。
     *
     * <p>多候选时：交互式问用户选哪个；headless 直接报用法错误 —— 不猜。
     * 「猜一个」在这种场景下代价很高：Agent 会在错误的目录里读写文件，而用户毫无察觉。</p>
     */
    private Optional<Workspace> pickWorkspace(CliOptions options, LineEditor editor, OutputSink out) {
        WorkspaceResolver.Result result = WorkspaceResolver.resolve(workspaces, options.workspace);
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
                        + (options.workspace == null ? "(未指定)" : options.workspace)));
                out.println(out.theme().dim("  用 -w <ID|名称|路径> 指定；不带 -w 会列出全部候选。"));
                return Optional.empty();
            }
            case AMBIGUOUS -> {
                List<Workspace> candidates = result.candidates();
                out.println(out.theme().yellow("存在多个可用工作空间，请明确指定一个（CLI 不代为猜测）："));
                for (int i = 0; i < candidates.size(); i++) {
                    out.println(String.format("  %d. %s", i + 1, WorkspaceResolver.describe(candidates.get(i))));
                }
                if (editor == null) {
                    out.println(out.theme().red("  非交互环境无法询问，请用 -w 指定后重试。"));
                    return Optional.empty();
                }
                String answer = editor.readLine("请选择序号（回车放弃）: ");
                if (answer == null || answer.isBlank()) {
                    out.println(out.theme().dim("已放弃。"));
                    return Optional.empty();
                }
                try {
                    int idx = Integer.parseInt(answer.trim());
                    if (idx < 1 || idx > candidates.size()) {
                        out.println(out.theme().red("序号超出范围。"));
                        return Optional.empty();
                    }
                    return Optional.of(candidates.get(idx - 1));
                } catch (NumberFormatException e) {
                    out.println(out.theme().red("请输入数字序号。"));
                    return Optional.empty();
                }
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------------ 会话

    /** 会话定位失败（用法错误，映射为退出码 2）。 */
    private static final class CliUsageException extends RuntimeException {
        CliUsageException(String message) {
            super(message);
        }
    }

    private SessionRef resolveSession(CliOptions options, Workspace workspace, OutputSink out) {
        if (options.resume != null && !options.resume.isBlank()) {
            var found = sessions.resolve(options.resume, LOCAL_USER);
            if (found.isEmpty()) {
                String hint = sessions.isAmbiguous(options.resume, LOCAL_USER)
                        ? "选择器命中多个会话，请给出更长的 ID 前缀。"
                        : "未找到该会话（用 --list-sessions 查看序号与 ID）。";
                throw new CliUsageException("恢复会话失败：" + hint);
            }
            return resumed(found.get(), options, workspace, out, "已恢复会话 ");
        }
        if (options.continueLast) {
            Optional<SessionRepository.SessionMeta> latest = sessions.latest(LOCAL_USER);
            if (latest.isPresent()) {
                return resumed(latest.get(), options, workspace, out, "已继续最近会话 ");
            }
            out.println(out.theme().dim("没有可继续的历史会话，已为你开启新会话。"));
        }
        // 新会话：本地生成 ID，首条消息落盘时才写元数据 ——
        // 避免每次启动 CLI 都留下一个空会话把 /sessions 刷满
        return new SessionRef(UUID.randomUUID().toString(), LOCAL_USER, workspace.workspaceId());
    }

    /** 组装「恢复/继续」的 SessionRef，并处理工作空间绑定冲突。 */
    private SessionRef resumed(SessionRepository.SessionMeta meta, CliOptions options,
                               Workspace requested, OutputSink out, String prefix) {
        String boundWs = meta.workspaceId() == null || meta.workspaceId().isBlank()
                ? requested.workspaceId() : meta.workspaceId();
        if (options.workspace != null && !options.workspace.isBlank()
                && !boundWs.equals(requested.workspaceId())) {
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

    private void warnUnknownOutputFormat(CliOptions options, OutputSink err) {
        if (!options.outputFormatValid()) {
            err.println(err.theme().yellow("未知的输出格式「" + options.outputFormat + "」，已回落为 text。"));
        }
    }
}
