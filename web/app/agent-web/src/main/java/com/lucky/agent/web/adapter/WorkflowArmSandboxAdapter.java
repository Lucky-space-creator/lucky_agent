package com.lucky.agent.web.adapter;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import com.lucky.agent.workflow.adapter.CommandResult;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

/**
 * 工作流命令沙箱 → 本机执行臂的桥接适配器（收口实现）。
 *
 * <p><b>解决什么问题：</b>工作流 {@code CODE} 节点原先由工作流模块自带的
 * {@link com.lucky.agent.workflow.adapter.LocalSandboxAdapter} 直接以 {@code ProcessBuilder}
 * 执行（{@code cmd /c} 或 {@code sh -c}），<b>完全绕过执行臂</b> —— 即无权限裁决
 * （{@code deny > ask > allow}）、无 realpath 硬边界、无 OS 红线检测、无断路器，
 * 成为工程内唯一能绕开全部边界执行命令的入口（违背决策 D11/D20「通道不得另起执行路径」）。</p>
 *
 * <p><b>为什么桥接类落在 agent-web：</b>决策 D32 规定 {@code agent-workflow} 不依赖任何内部
 * sibling 模块，故本模块不能直接引用 {@code agent-executor}。而 {@code agent-web} 同时依赖两者，
 * 是唯一能同时看见「工作流适配器契约」与「执行臂服务」的位置，因此由它提供实现并覆盖默认 Bean
 * （工作流侧对该 Bean 标注了 {@code @ConditionalOnMissingBean}，本就是为此预留的集成缝隙）。</p>
 *
 * <p><b>安全语义（与 Agent 工具调用完全对齐）</b></p>
 * <ul>
 *     <li>命令一律经 {@link FileService#exec} 进入执行臂，从而获得 realpath 越界防护、
 *         {@code deny > ask > allow} 规则链、内置敏感路径/破坏性命令 DENY 基线与 OS 红线拦截。</li>
 *     <li>执行臂返回的 <b>DENY / ASK / 越界 / 红线命中一律映射为「拦截」</b>
 *         （{@code CommandResult.blocked}，退出码 {@code -1}）。工作流在后台线程运行，
 *         <b>没有任何人能应答 ASK 确认</b>；若在此静默放行，等于用「无人值守」把权限链整段跳过。</li>
 *     <li>沙箱开关沿用工作流既有语义（{@code lucky.workflow.sandbox.enabled}，默认关闭）。
 *         本适配器<b>不改变默认行为</b>：只有用户显式开启后，命令才会从「模块自执行」变为
 *         「交执行臂执行」——开启与否的风险差异由此收敛为纯安全性提升。</li>
 * </ul>
 */
@Slf4j
public class WorkflowArmSandboxAdapter implements SandboxAdapter {

    /** 未指定超时时的等待上限（与工作流模块默认值一致）。 */
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private final FileService fileService;
    private final WorkspaceManager workspaceManager;
    private final String configuredWorkspaceId;
    private final boolean enabled;

    public WorkflowArmSandboxAdapter(FileService fileService, WorkspaceManager workspaceManager,
                                     String configuredWorkspaceId, boolean enabled) {
        this.fileService = fileService;
        this.workspaceManager = workspaceManager;
        this.configuredWorkspaceId = configuredWorkspaceId;
        this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public CommandResult run(String command, long timeoutMs) {
        if (!enabled) {
            return CommandResult.blocked("命令沙箱未启用（需显式授权 lucky.workflow.sandbox.enabled=true）");
        }
        if (command == null || command.isBlank()) {
            return CommandResult.blocked("命令为空");
        }
        Optional<Workspace> workspace = resolveWorkspace();
        if (workspace.isEmpty()) {
            String reason = configuredWorkspaceId == null || configuredWorkspaceId.isBlank()
                    ? "未找到内置默认工作空间（启动注册失败）"
                    : "配置的工作空间不存在：" + configuredWorkspaceId;
            log.warn("工作流命令被拦截，无法解析工作空间：{}", reason);
            return CommandResult.blocked("工作流命令沙箱无可用工作空间，" + reason
                    + "。请设置 lucky.workflow.sandbox.workspace-id");
        }

        String workspaceId = workspace.get().workspaceId();
        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        long start = System.currentTimeMillis();
        ExecResult result;
        try {
            result = fileService.exec(workspaceId, command).block(Duration.ofMillis(timeout));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("工作流命令执行异常：wid={} command={} err={}", workspaceId, command, e.toString());
            return new CommandResult(-1, "", "命令执行等待超时或异常（上限 " + timeout + "ms）：" + e.getMessage(),
                    true, elapsed);
        }
        long elapsed = System.currentTimeMillis() - start;
        if (result == null) {
            return new CommandResult(-1, "", "命令执行无返回（执行臂不可用）", false, elapsed);
        }
        if (!result.ok()) {
            // 执行臂的 DENY / ASK（ExecResult.ASK_REQUIRED）/ 越界 / OS 红线均落在这里。
            // 后台运行无人应答确认，故一律按拦截处理，绝不静默放行。
            log.warn("工作流命令被执行臂拦截：wid={} command={} reason={}",
                    workspaceId, command, result.error());
            return new CommandResult(-1, "", safeError(result), false, elapsed);
        }
        // 执行臂成功：CmdSandbox 以 summary="exit=N" 回传退出码，stderr 已并入 stdout
        // （ProcessBuilder.redirectErrorStream(true)），故 stderr 恒为空串。
        return new CommandResult(parseExitCode(result.summary()),
                result.content() == null ? "" : result.content(), "", false, elapsed);
    }

    /**
     * 解析工作空间：显式配置优先，未配置则回退内置默认工作空间。
     *
     * <p>回退依据 {@link Workspace#builtin()}（启动流程注册的兜底工作空间），
     * 而非硬编码名称 —— 名称是展示文案，可能被改动。</p>
     */
    private Optional<Workspace> resolveWorkspace() {
        if (configuredWorkspaceId != null && !configuredWorkspaceId.isBlank()) {
            return workspaceManager.findById(configuredWorkspaceId);
        }
        return workspaceManager.listWorkspaces().stream().filter(Workspace::builtin).findFirst();
    }

    /** 从执行臂摘要 {@code "exit=N"} 中解析退出码；无法解析时按成功（0）处理。 */
    private static int parseExitCode(String summary) {
        if (summary == null) {
            return 0;
        }
        String trimmed = summary.trim();
        if (trimmed.startsWith("exit=")) {
            try {
                return Integer.parseInt(trimmed.substring("exit=".length()).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String safeError(ExecResult r) {
        return r.error() == null || r.error().isBlank() ? "执行臂拒绝执行（原因未知）" : r.error();
    }
}