package com.lucky.agent.cli.repl;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.CliTurnExecutor;
import com.lucky.agent.cli.CliTurnLoop;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.command.CommandContext;
import com.lucky.agent.cli.command.SlashCommandRegistry;
import com.lucky.agent.cli.input.FileReference;
import com.lucky.agent.cli.input.ShellPrefix;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.cli.term.LineEditor;
import com.lucky.agent.cli.workspace.WorkspaceResolver;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.constant.PermissionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 交互式主循环。
 *
 * <p>一行的归属只有四种可能，判定顺序固定：{@code /} → 命令；{@code !} → 执行臂直执行；
 * {@code @} → 文件引用；其余 → 提示词。判定放在最前面、且互斥，避免出现「以 {@code !} 开头的
 * 提示词被当成命令」这种歧义。</p>
 *
 * <p>循环本身不含任何内核逻辑：提交、授权、选项、收尾全在 {@link CliTurnLoop}；本类只负责
 * 「读一行 → 分发 → 展示状态」。</p>
 */
public final class ReplLoop {

    private final OutputSink out;
    private final LineEditor editor;
    private final CliOptions options;
    private final CommandContext ctx;
    private final SlashCommandRegistry registry;
    private final CliTurnLoop turns;
    private final ApprovalHandler approval;
    private final SessionHolder holder;

    /** 本进程累计 token（跨轮），用于状态栏的累计值。 */
    private long totalTokens;

    /**
     * @param executor 单轮执行器（负责「先订阅后提交」的时序）
     * @param approval ASK 授权裁决
     *
     * <p>这里在构造期<b>就地</b>组装 {@link CliTurnLoop}，而不是从外部传一个现成的进来：
     * 状态上报回调需要访问本类的累计 token 与当前会话，外部无法在构造本类之前提供它。
     * 若把回调做成可后期注入，就会引入「注入前的那一轮不上报状态」这类时序缺口。</p>
     */
    public ReplLoop(OutputSink out, LineEditor editor, CliOptions options, CommandContext ctx,
                    SlashCommandRegistry registry, CliTurnExecutor executor,
                    ApprovalHandler approval, SessionHolder holder) {
        this.out = out;
        this.editor = editor;
        this.options = options;
        this.ctx = ctx;
        this.registry = registry;
        this.holder = holder;
        this.approval = approval;
        this.turns = new CliTurnLoop(executor, approval, out, this::reportStatus);
        ctx.registry(registry);
    }

    /** 运行 REPL，返回进程退出码。 */
    public int run() {
        printBanner();
        while (true) {
            String line;
            try {
                line = editor.readLine(prompt());
            } catch (Exception e) {
                out.println(out.theme().red("终端读取失败：" + e.getMessage()));
                break;
            }
            if (line == null || LineEditor.EXIT.equals(line)) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            editor.addHistory(trimmed);

            // 单条输入的任何异常都不应带走会话：`!` 命令、`@` 文件展开、模型往返、
            // 自定义命令都可能抛（IO、子进程、网络）。此前只有 readLine 有 try，
            // 一次 IOException 就会让整个 REPL 直接退出。
            try {
                if (handleLine(trimmed)) {
                    break;
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName() : e.getMessage();
                out.println(out.theme().red("本轮处理失败：" + msg));
            }
        }
        out.println(out.theme().dim("再见。"));
        return CliExitCode.SUCCESS;
    }

    /**
     * 处理一条已去空白的输入。
     *
     * @return {@code true} 表示应退出 REPL
     */
    private boolean handleLine(String trimmed) {
        // ① slash 命令
        if (trimmed.charAt(0) == SlashCommandRegistry.PREFIX) {
            if (registry.dispatch(trimmed, ctx) == SlashCommandRegistry.Outcome.EXIT) {
                return true;
            }
            // 自定义命令（commands/*.md）可能登记了一个待提交的提示词，分发后作为一轮运行
            String pending = ctx.consumePendingPrompt();
            if (pending != null && !pending.isBlank()) {
                runTurn(pending);
            }
            return false;
        }
        // ② ! 直执行（高危确认与 Agent 路径共用同一 ApprovalHandler）
        if (ShellPrefix.matches(trimmed)) {
            ShellPrefix.run(trimmed, holder.workspaceId(), ctx.services().files(),
                    approval, ctx.services().permissions(), out);
            return false;
        }
        // ③ @ 文件引用
        String content = trimmed;
        if (FileReference.matches(trimmed)) {
            String expanded = FileReference.expand(trimmed, holder.workspaceId(),
                    ctx.services().files(), out);
            if (expanded == null) {
                return false;
            }
            content = expanded;
        }
        // ④ 提示词
        runTurn(content);
        return false;
    }

    // ------------------------------------------------------------------ 一轮运行

    private void runTurn(String content) {
        CliTurnLoop.Result result = turns.run(holder.ref(), content, baseExtra(), this::chooseOption);
        TurnOutcome outcome = result.outcome();
        if (outcome == null) {
            return;
        }
        if (outcome.failed()) {
            if (outcome.cancelled()) {
                // 用户按 Ctrl+C 取消是预期结果，不该用「失败」通报（实测曾被报成「本轮失败：已取消」）
                out.println(out.theme().dim("已取消本轮。"));
            } else {
                out.println(out.theme().red("本轮失败：" + outcome.errorText()));
            }
        } else if (result.askDenied()) {
            out.println(out.theme().yellow("已按你的选择拒绝该操作。"));
        }
    }

    /** 每步收尾打印状态行（状态栏的落点是轮次边界，理由见 {@link StatusBar}）。 */
    private void reportStatus(TurnOutcome outcome) {
        if (outcome == null || outcome.capture() == null) {
            return;
        }
        long turnTokens = outcome.capture().tokenUsed();
        totalTokens += turnTokens;
        StatusBar.print(out, StatusBar.format(
                modelOf(outcome),
                phaseOf(),
                permissionOf(),
                holder.shortId(),
                turnTokens,
                totalTokens,
                outcome.durationMs()));
    }

    private String modelOf(TurnOutcome outcome) {
        if (outcome.result() != null && outcome.result().model() != null
                && !outcome.result().model().isBlank()) {
            return outcome.result().model();
        }
        return outcome.capture() == null ? null : outcome.capture().model();
    }

    private String phaseOf() {
        return ctx.services().stateManager().find(holder.sessionId())
                .map(s -> s.phase()).map(Phase::name).orElse(null);
    }

    private String permissionOf() {
        return ctx.services().workspaces().permissionLevelOf(holder.workspaceId())
                .map(PermissionLevel::getLabel).orElse(null);
    }

    /**
     * 基础附加参数：模型与预算覆盖（内核 {@code ConversationManager#buildCtx} 会透传到 {@code ctx.extra}）。
     *
     * <p>取值优先级「命令行 &gt; 环境变量 &gt; 项目本地配置」由 {@code CliConfigResolver} 统一裁决 ——
     * 项目配置因此真正生效（此前只用于 {@code /doctor} 展示，run 路径只看命令行）。</p>
     */
    private Map<String, Object> baseExtra() {
        return ctx.services().configResolver().effectiveRunExtra(
                options.model, options.maxTurns, options.maxBudget, ctx.project());
    }

    /** 条件选择：让用户在终端选一个；返回选项 label（内核以它作为新的用户输入续跑）。 */
    private String chooseOption(String question, List<Map<String, Object>> optionList) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        String answer = editor.readLine("请选择（输入序号，或直接输入你的想法）: ");
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String a = answer.trim();
        if (a.chars().allMatch(Character::isDigit)) {
            // 台账 #42：超长纯数字串（如误把整段文本粘进选择框）会让 parseInt 抛
            // NumberFormatException 穿透整个 REPL —— 序号解析必须兜住，越界/解析失败
            // 一律降级为「自定义输入」，绝不能让用户被踢出进程。
            int idx = parseOptionIndex(a);
            if (idx >= 1 && idx <= optionList.size()) {
                Object label = optionList.get(idx - 1).get("label");
                if (label != null && !String.valueOf(label).isBlank()) {
                    return String.valueOf(label);
                }
            }
            out.println(out.theme().yellow("序号超出范围或不可解析，已按自定义输入处理。"));
        }
        return a;
    }

    /** 选项序号解析：解析失败（超长数字串等）返回 -1，由调用方按越界处理（包可见便于单测）。 */
    static int parseOptionIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 展示

    private String prompt() {
        return out.theme().cyan("› ");
    }

    /**
     * 启动打印：品牌字标（{@link Banner}）+ 无条件回显「当前工作空间 + 当前会话」，
     * 让用户明确 Agent 会在哪个目录里干活。
     *
     * <p>这里只负责<b>取材</b>（工作空间 / 会话 / 终端），排版与着色交给 {@code Banner} ——
     * 本类不该知道色号与转义序列。</p>
     */
    private void printBanner() {
        Theme t = out.theme();
        List<String> details = new ArrayList<>();
        details.add(Banner.detail(t, "工作空间",
                ctx.services().workspaces().getWorkspace(holder.workspaceId())
                        // 复用 WorkspaceResolver.describe（name [id8] · 权限 · 路径 · 内置默认）：
                        // §4.2 要求 Banner 必须打出所选工作空间的 ID，本地另一份实现会漏掉它
                        .map(WorkspaceResolver::describe)
                        .orElse(t.red("(会话绑定的工作空间已失效)"))));
        details.add(Banner.detail(t, "会话", holder.sessionId()));
        details.add(Banner.detail(t, "终端", (t.enabled() ? "交互式（ANSI）" : "纯文本（非 TTY 或 --plain）")
                + "；/help 看命令；Ctrl+C 取消本轮，提示符处连按两次 Ctrl+C 退出"));
        if (!t.enabled()) {
            // 非 TTY 也能进会话（IDE 运行控制台 / 管道 / CI）：逐行读 stdin，EOF 或 /exit 即退出。
            // 显式说明，避免用户以为是「卡住」。
            details.add(Banner.detail(t, "说明", "纯文本模式：逐行读取标准输入，输入结束或 /exit 即退出；"
                    + "一次性执行请用 -p"));
        }
        Banner.print(out, CliOptions.VERSION, details);
    }
}
