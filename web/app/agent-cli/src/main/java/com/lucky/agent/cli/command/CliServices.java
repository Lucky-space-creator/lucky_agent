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
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.skill.service.SkillRegistry;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workspace.api.WorkspaceConfig;

/**
 * CLI 侧依赖集合：把装配来的内核 Bean 打包一次传递，避免每个命令各自声明一长串构造参数。
 *
 * <p>只装「命令真的会用到」的 Bean。刻意不装 {@code AgentChannel}、{@code Orchestrator} 等运行链路组件 ——
 * 命令层只做查看与会话管理，不参与运行，把运行链路的入口收敛在 {@code CliTurnExecutor} 一处。</p>
 *
 * <p><b>扩展能力的三个 registry</b>（{@code /mcp}、{@code /skills}）：</p>
 * <ul>
 *   <li>{@code mcp}／{@code mcpConnector}：前者列配置（{@code LocalMcpRegistry}），后者读运行时连接状态
 *       （{@code ConnectionManager}）。分开是因为「有配置」与「连上了」是两回事，且读状态不应触发连接。</li>
 *   <li>{@code skills}：列可用技能（{@code LocalSkillRegistry}）。</li>
 * </ul>
 * <p>三者均为只读查看，不改变任何状态 —— 与命令层「只查看、不执行」的定位一致。</p>
 */
public record CliServices(CliSessionService sessions,
                          ConversationManager conversations,
                          ConversationStateManager stateManager,
                          WorkspaceConfig workspaces,
                          WorkspaceDirs dirs,
                          MetricsCollector metrics,
                          FileService files,
                          PermissionService permissions,
                          CliConfigResolver configResolver,
                          McpRegistry mcp,
                          McpConnector mcpConnector,
                          SkillRegistry skills) {
}
