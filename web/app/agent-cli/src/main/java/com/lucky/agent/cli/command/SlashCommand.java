package com.lucky.agent.cli.command;

import java.util.List;

/**
 * 一个 slash 命令。
 *
 * <p><b>为什么命令层不用 Picocli 定义</b>：Picocli 的命令模型是「命令名 + 选项 + 位置参数」，
 * 而 REPL 的输入语法是 {@code /cmd}、{@code !shell}、{@code @file} 三种前缀并存。
 * 把前缀语法硬塞进 Picocli 需要自定义 {@code CommandSpec} 与解析器，反而比手写分发复杂，
 * 且会引入「终端里的解析」与「启动时的解析」两套不一致的规则。
 * 故：启动期参数交给 Picocli（单 jar、零传递依赖、自带 {@code --help}），
 * REPL 内的命令由本接口 + {@link SlashCommandRegistry} 手写分发。</p>
 */
public interface SlashCommand {

    /** 主名称（不含前导斜杠）。 */
    String name();

    /** 别名（不含前导斜杠）。 */
    default List<String> aliases() {
        return List.of();
    }

    /** 一行说明（用于 {@code /help}）。 */
    String summary();

    /** 用法串。 */
    default String usage() {
        return "/" + name();
    }

    /**
     * 执行。
     *
     * @param args 已按空白切分、且已去掉命令本身的参数
     * @return 是否请求退出 REPL
     */
    boolean run(CommandContext ctx, List<String> args);
}
