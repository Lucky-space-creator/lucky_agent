package com.lucky.agent.cli.command;

import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.session.CliSessionService;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workspace.api.WorkspaceConfig;

/**
 * CLI 侧依赖集合：把装配来的内核 Bean 打包一次传递，避免每个命令各自声明一长串构造参数。
 *
 * <p>只装「命令真的会用到」的 Bean。刻意不装 {@code AgentChannel}、{@code Orchestrator} 等运行链路组件 ——
 * 命令层只做查看与会话管理，不参与运行，把运行链路的入口收敛在 {@code CliTurnExecutor} 一处。</p>
 */
public record CliServices(CliSessionService sessions,
                          ConversationManager conversations,
                          ConversationStateManager stateManager,
                          WorkspaceConfig workspaces,
                          WorkspaceDirs dirs,
                          MetricsCollector metrics,
                          FileService files,
                          PermissionService permissions,
                          CliConfigResolver configResolver) {
}
