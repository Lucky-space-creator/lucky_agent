package com.lucky.agent.cli.command;

import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.session.SessionHolder;

/**
 * 命令执行上下文：命令需要的「环境 + 可变状态 + 回调」都在这里。
 *
 * <p>注册表通过 {@link #registry(SlashCommandRegistry)} 回填（先建上下文、后建注册表，
 * 而 {@code /help} 又需要注册表来列举全部命令 —— 这个先后依赖是真实存在的，用回填而不是
 * 造一个可空单例来绕）。</p>
 */
public final class CommandContext {

    private final OutputSink out;
    private final CliServices services;
    private final CliOptions options;
    /** 项目本地配置：可被 {@code /reload} 在会话内替换（对齐「改完保存即生效」）。 */
    private CliConfigResolver.ProjectConfig project;
    private final SessionHolder holder;
    private final Runnable exitRequest;

    private SlashCommandRegistry registry;

    /** 待提交提示词（自定义命令 {@code commands/*.md} 设置，REPL 分发后消费并作为一轮运行）。 */
    private String pendingPrompt;

    public CommandContext(OutputSink out, CliServices services, CliOptions options,
                          CliConfigResolver.ProjectConfig project, SessionHolder holder,
                          Runnable exitRequest) {
        this.out = out;
        this.services = services;
        this.options = options;
        this.project = project;
        this.holder = holder;
        this.exitRequest = exitRequest;
    }

    public OutputSink out() {
        return out;
    }

    public CliServices services() {
        return services;
    }

    public CliOptions options() {
        return options;
    }

    /** 项目本地配置（含「被拒绝加载」的状态，{@code /doctor} 要展示）。 */
    public CliConfigResolver.ProjectConfig project() {
        return project;
    }

    /** 用重新读取的项目本地配置替换当前上下文中的配置（由 {@code /reload} 调用）。 */
    public void reloadProject(CliConfigResolver.ProjectConfig project) {
        this.project = project;
    }

    public SessionHolder holder() {
        return holder;
    }

    /** 请求退出 REPL（由 {@code /exit} 调用）。 */
    public void requestExit() {
        exitRequest.run();
    }

    public SlashCommandRegistry registry() {
        return registry;
    }

    public void registry(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 登记一个待提交的提示词（自定义命令用）：REPL 在分发结束后会消费它并作为一轮运行。
     * 与直接运行的区别是——命令本身只负责「产出提示词」，真正的提交仍走统一的单轮执行链路。
     */
    public void runPrompt(String text) {
        this.pendingPrompt = text;
    }

    /** 取出并清空待提交提示词（REPL 调用一次后失效）。 */
    public String consumePendingPrompt() {
        String p = pendingPrompt;
        pendingPrompt = null;
        return p;
    }
}
