package com.lucky.agent.cli.repl;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.CliTurnExecutor;
import com.lucky.agent.cli.CliTurnLoop;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.command.CommandContext;
import com.lucky.agent.cli.command.SlashCommandRegistry;
import com.lucky.agent.cli.input.FileReference;
import com.lucky.agent.cli.input.ShellPrefix;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.cli.term.LineEditor;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.workspace.api.dto.Workspace;

import java.util.LinkedHashMap;
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
    private final SessionHolder holder;

    /** 退出标记（由 {@code /exit} 的返回值驱动，这里保留字段便于将来加「外部要求退出」的入口）。 */
    private volatile boolean exitRequested;

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
        this.turns = new CliTurnLoop(executor, approval, out, this::reportStatus);
        ctx.registry(registry);
    }

    /** 运行 REPL，返回进程退出码。 */
    public int run() {
        printBanner();
        while (!exitRequested) {
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

            // ① slash 命令
            if (trimmed.charAt(0) == SlashCommandRegistry.PREFIX) {
                if (registry.dispatch(trimmed, ctx) == SlashCommandRegistry.Outcome.EXIT) {
                    break;
                }
                continue;
            }
            // ② ! 直执行
            if (ShellPrefix.matches(trimmed)) {
                ShellPrefix.run(trimmed, holder.workspaceId(), ctx.services().files(),
                        ctx.services().permissions(), out, editor);
                continue;
            }
            // ③ @ 文件引用
            String content = trimmed;
            if (FileReference.matches(trimmed)) {
                String expanded = FileReference.expand(trimmed, holder.workspaceId(),
                        ctx.services().files(), out);
                if (expanded == null) {
                    continue;
                }
                content = expanded;
            }
            // ④ 提示词
            runTurn(content);
        }
        out.println(out.theme().dim("再见。"));
        return CliExitCode.SUCCESS;
    }

    // ------------------------------------------------------------------ 一轮运行

    private void runTurn(String content) {
        CliTurnLoop.Result result = turns.run(holder.ref(), content, baseExtra(), this::chooseOption);
        TurnOutcome outcome = result.outcome();
        if (outcome == null) {
            return;
        }
        if (outcome.failed()) {
            out.println(out.theme().red("本轮失败：" + outcome.errorText()));
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

    /** 基础附加参数：模型与预算覆盖（内核 {@code ConversationManager#buildCtx} 会透传到 {@code ctx.extra}）。 */
    private Map<String, Object> baseExtra() {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (options.model != null && !options.model.isBlank()) {
            extra.put("modelId", options.model.trim());
        }
        putIfPresent(extra, "maxTurns", options.maxTurns);
        putIfPresent(extra, "maxBudget", options.maxBudget);
        return extra;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
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
            int idx = Integer.parseInt(a);
            if (idx >= 1 && idx <= optionList.size()) {
                Object label = optionList.get(idx - 1).get("label");
                if (label != null && !String.valueOf(label).isBlank()) {
                    return String.valueOf(label);
                }
            }
            out.println(out.theme().yellow("序号超出范围，已按自定义输入处理。"));
        }
        return a;
    }

    // ------------------------------------------------------------------ 展示

    private String prompt() {
        return out.theme().cyan("› ");
    }

    /** 启动横幅：无条件回显「当前工作空间 + 当前会话」，让用户明确 Agent 会在哪个目录里干活。 */
    private void printBanner() {
        out.println(out.theme().bold("Lucky Agent CLI ") + out.theme().dim("v" + CliOptions.VERSION));
        out.println(out.theme().dim("本地优先 · 会话/记忆/文件只在本机"));
        out.println("  工作空间：" + ctx.services().workspaces().getWorkspace(holder.workspaceId())
                .map(ReplLoop::describeWorkspace)
                .orElse(out.theme().red("(会话绑定的工作空间已失效)")));
        out.println("  会话：" + holder.sessionId());
        out.println(out.theme().dim("  终端：" + (out.theme().enabled() ? "交互式（ANSI）" : "纯文本（非 TTY 或 --plain）")
                + "；/help 看命令；Ctrl+C 取消本轮，提示符处连按两次 Ctrl+C 退出"));
        out.println();
    }

    static String describeWorkspace(Workspace w) {
        PermissionLevel level = w.permissionLevel() == null
                ? PermissionLevel.defaultValue() : w.permissionLevel();
        return (w.name() == null ? "(未命名)" : w.name())
                + " · 权限 " + level.getLabel()
                + " · " + (w.path() == null ? "(无路径)" : w.path())
                + (w.builtin() ? " " + "[内置默认]" : "");
    }
}
