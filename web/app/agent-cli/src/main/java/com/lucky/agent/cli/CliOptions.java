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
 *
 * <p><b>授权代答与条件选择是两个独立开关</b>：{@code --auto-approve} 只处理「高危操作授权」，
 * {@code --auto-pick} 只处理「内核给出的条件选择」。此前二者共用一个开关，导致脚本化放行授权时
 * 会<b>顺带替用户做条件选择</b> —— 那是两个不相干的安全语义，必须分开，否则无法只放行其一。</p>
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

    @Option(names = {"--cwd"}, paramLabel = "<路径>",
            description = "项目本地配置与自定义命令的查找起点（默认取进程 CWD）")
    public String cwd;

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
            description = "自动代答「允许一次」高危授权（仅授权，不涉及条件选择；仍受执行臂权限级别与边界约束，请谨慎用于脚本）")
    public boolean autoApprove;

    @Option(names = {"--auto-pick"},
            description = "自动选择内核给出的条件选项（取「推荐项」或第一项；与 --auto-approve 相互独立）")
    public boolean autoPick;

    // ---------------------------------------------------------------- 输出形态

    @Option(names = {"--output-format"}, paramLabel = "text|json|stream-json",
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

    // 注：run 级附加参数（modelId/maxTurns/maxBudget）的取值与组装统一在
    // CliConfigResolver#effectiveRunExtra（命令行 > 环境变量 > 项目本地配置），
    // 本类不再自行派生，避免同一份键映射散落两处。

    /**
     * 输出格式规范化（不合法值回落 text）。
     *
     * <p>刻意做成静态方法：{@code --output-format} 未显式给出时为 {@code null}（不带 defaultValue），
     * 这样它才能参与「命令行 &gt; 环境变量 &gt; 项目本地配置」三级解析；解析后的字符串再交给本方法
     * 规范化，避免「命令行缺省值」把项目配置这一层永久顶掉。</p>
     */
    public static String normalizeOutputFormat(String raw) {
        String f = raw == null ? "text" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (f) {
            case "json", "stream-json", "text" -> f;
            default -> "text";
        };
    }

    /** 输出格式规范化（基于本实例的命令行取值）。 */
    public String normalizedOutputFormat() {
        return normalizeOutputFormat(outputFormat);
    }

    /** 给定原始值是否为合法输出格式（null/空白视为合法，走默认）。 */
    public static boolean outputFormatValid(String raw) {
        return raw == null || raw.isBlank() || normalizeOutputFormat(raw).equals(raw.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 命令行给出的输出格式是否为合法取值。 */
    public boolean outputFormatValid() {
        return outputFormatValid(outputFormat);
    }
}
