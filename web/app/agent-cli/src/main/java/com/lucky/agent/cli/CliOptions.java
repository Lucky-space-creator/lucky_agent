package com.lucky.agent.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动参数。
 *
 * <p><b>为什么启动期参数用 Picocli，而 REPL 内的命令手写分发</b>（CLI 方案 v2 §1）：Picocli 的
 * 命令模型是「命令名 + 选项 + 位置参数」，用它能以零成本得到 {@code --help}、错误提示与退出码；
 * 而 REPL 的输入语法是三种前缀并存（{@code /}、{@code !}、{@code @}），Picocli 不表达前缀语义，
 * 硬塞需要自定义解析器，反而更复杂，还会产生「终端内解析」与「启动时解析」两套不一致的规则。
 * 分工是清晰的：<b>启动期 → Picocli；会话内 → 手写分发器</b>。</p>
 *
 * <p>刻意没有 {@code --dangerously-skip-permissions} 之类的权限逃逸开关：权限由执行臂硬边界强制
 * （决策 D2），CLI 不提供绕过通道。{@code --auto-approve} 只是在<b>需要询问时</b>代答「允许一次」，
 * 不放宽工作空间权限级别，也不越过 realpath 边界。</p>
 */
@Command(name = "lucky",
        description = "Lucky Agent CLI —— 本地优先的智能体命令行（复用 Web 端同一内核/记忆/执行臂）")
public class CliOptions {

    // ---------------------------------------------------------------- 运行形态

    @Option(names = {"-p", "--prompt"}, paramLabel = "<提示词>",
            description = "一次性执行：执行完即退出（headless），适合脚本与管道")
    public String prompt;

    @Parameters(arity = "0..*", paramLabel = "[提示词]",
            description = "位置参数形式的提示词，等价于 -p")
    public List<String> positional = new ArrayList<>();

    @Option(names = {"-c", "--continue"}, description = "继续最近一次会话")
    public boolean continueLast;

    @Option(names = {"-r", "--resume"}, paramLabel = "<序号|ID|ID前缀>",
            description = "恢复指定历史会话（序号见 --list-sessions）")
    public String resume;

    @Option(names = {"-w", "--workspace"}, paramLabel = "<ID|名称|路径>",
            description = "指定工作空间；缺省时自动挑选，多候选则询问")
    public String workspace;

    @Option(names = {"--list-sessions"}, description = "列出历史会话后退出")
    public boolean listSessions;

    // ---------------------------------------------------------------- 运行参数

    @Option(names = {"--model"}, paramLabel = "<模型ID>", description = "覆盖本轮使用的主模型")
    public String model;

    @Option(names = {"--max-turns"}, paramLabel = "<N>", description = "覆盖单轮回合上限（内核默认见 CoreProperties）")
    public String maxTurns;

    @Option(names = {"--max-budget"}, paramLabel = "<N>", description = "覆盖单轮 token 预算")
    public String maxBudget;

    @Option(names = {"--auto-approve"},
            description = "自动代答「允许一次」高危确认（仍受执行臂权限级别与边界约束，请谨慎用于脚本）")
    public boolean autoApprove;

    // ---------------------------------------------------------------- 输出形态

    @Option(names = {"--output-format"}, paramLabel = "text|json|stream-json", defaultValue = "text",
            description = "headless 输出格式：text=仅正文（默认，stdout 为正文、stderr 为进度）；"
                    + "json=结束时输出单个 JSON；stream-json=逐事件 NDJSON")
    public String outputFormat;

    @Option(names = {"--plain"}, description = "纯文本模式：禁用颜色与行编辑（非 TTY 时自动生效）")
    public boolean plain;

    @Option(names = {"--show-thinking"}, description = "完整展示推理链（默认只显示一行摘要）")
    public boolean showThinking;

    // ---------------------------------------------------------------- 标准选项

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "显示本帮助并退出")
    public boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "显示版本并退出")
    public boolean version;

    /** CLI 版本（与 Maven 模块版本解耦：这里是交互契约版本，不随内核补丁号变动而变）。 */
    public static final String VERSION = "1.0.0";

    /** 生效的提示词（{@code -p} 优先；否则取位置参数合并）；为空表示进入交互式 REPL。 */
    public String promptText() {
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        if (positional == null || positional.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", positional).trim();
        return joined.isEmpty() ? null : joined;
    }

    /** 是否 headless（有提示词就一次性跑完退出）。 */
    public boolean headless() {
        return promptText() != null;
    }

    /** 输出格式规范化（不合法值回落 text，并在 REPL/headless 启动时提示）。 */
    public String normalizedOutputFormat() {
        String f = outputFormat == null ? "text" : outputFormat.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (f) {
            case "json", "stream-json", "text" -> f;
            default -> "text";
        };
    }

    /** 输出格式是否为合法取值。 */
    public boolean outputFormatValid() {
        return outputFormat == null || outputFormat.isBlank()
                || normalizedOutputFormat().equals(outputFormat.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 本轮运行的附加参数（会被内核 {@code ConversationManager#buildCtx} 透传进 {@code ctx.extra}）。
     *
     * <p>键名必须与内核侧的读取口径一致：{@code modelId} 由 {@code ConversationManager} 直接读取；
     * {@code maxTurns}/{@code maxBudget} 由 {@code RunOverrides} 的白名单消费
     * （{@code extra} 里出现白名单之外的键会被忽略，这是刻意的收敛）。</p>
     */
    public java.util.Map<String, Object> runExtra() {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        if (model != null && !model.isBlank()) {
            extra.put("modelId", model.trim());
        }
        if (maxTurns != null && !maxTurns.isBlank()) {
            extra.put("maxTurns", maxTurns.trim());
        }
        if (maxBudget != null && !maxBudget.isBlank()) {
            extra.put("maxBudget", maxBudget.trim());
        }
        return extra;
    }
}
