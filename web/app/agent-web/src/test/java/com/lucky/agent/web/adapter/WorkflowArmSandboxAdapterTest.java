package com.lucky.agent.web.adapter;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import com.lucky.agent.workflow.adapter.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流命令节点收口到执行臂的桥接回归守卫。
 *
 * <p><b>为什么必须守这一层：</b>这是工程内此前唯一能绕开全部安全边界的入口
 * （工作流自带的 {@code LocalSandboxAdapter} 直接 {@code ProcessBuilder}）。
 * 收口后最关键的不变量是「执行臂的拒绝/ASK 绝不被翻译成成功」—— 工作流在后台线程运行，
 * 无人能应答确认，一旦把 ASK 当成功放行，权限链就被整段跳过。</p>
 */
class WorkflowArmSandboxAdapterTest {

    private static final String CMD = "echo hi";

    @Test
    @DisplayName("未启用沙箱时不触达执行臂，直接拦截")
    void disabledBlocksWithoutTouchingArm() {
        FileService files = mock(FileService.class);
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, workspaceManager("w1", true), null, false);

        CommandResult r = adapter.run(CMD, 1000);

        assertFalse(adapter.enabled());
        assertEquals(-1, r.exitCode());
        assertFalse(r.success());
        verify(files, never()).exec(anyString(), anyString());
    }

    @Test
    @DisplayName("执行臂成功：退出码从 summary 解析，stdout 取自 content")
    void successMapsExitCodeAndStdout() {
        FileService files = mock(FileService.class);
        when(files.exec("w1", CMD)).thenReturn(Mono.just(ExecResult.success(FileOp.OpType.EXEC, "req-1")
                .summary("exit=0").content("hi\n")));
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, workspaceManager("w1", true), null, true);

        CommandResult r = adapter.run(CMD, 1000);

        assertTrue(r.success());
        assertEquals(0, r.exitCode());
        assertEquals("hi\n", r.stdout());
        assertFalse(r.timedOut());
    }

    @Test
    @DisplayName("非零退出码被保留（供 failOnError 判定）")
    void nonZeroExitCodePreserved() {
        FileService files = mock(FileService.class);
        when(files.exec("w1", CMD)).thenReturn(Mono.just(ExecResult.success(FileOp.OpType.EXEC, "req-2")
                .summary("exit=3").content("")));
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, workspaceManager("w1", true), null, true);

        CommandResult r = adapter.run(CMD, 1000);

        assertEquals(3, r.exitCode());
        assertFalse(r.success());
    }

    @Test
    @DisplayName("执行臂 ASK（高危需确认）必须映射为拦截，绝不放行")
    void askFromArmBecomesBlocked() {
        FileService files = mock(FileService.class);
        when(files.exec("w1", CMD)).thenReturn(Mono.just(
                ExecResult.failure(FileOp.OpType.EXEC, "req-3", ExecResult.ASK_REQUIRED)));
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, workspaceManager("w1", true), null, true);

        CommandResult r = adapter.run(CMD, 1000);

        assertEquals(-1, r.exitCode());
        assertFalse(r.success(), "ASK 不得被当成成功");
        assertTrue(r.stderr().contains(ExecResult.ASK_REQUIRED), r.stderr());
    }

    @Test
    @DisplayName("显式配置的工作空间优先于内置默认工作空间")
    void configuredWorkspaceTakesPrecedence() {
        FileService files = mock(FileService.class);
        when(files.exec("w-configured", CMD)).thenReturn(Mono.just(
                ExecResult.success(FileOp.OpType.EXEC, "req-4").summary("exit=0").content("ok")));
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.findById("w-configured")).thenReturn(Optional.of(new Workspace().workspaceId("w-configured")));
        when(manager.listWorkspaces()).thenReturn(List.of(new Workspace().workspaceId("w-builtin").builtin(true)));
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, manager, "w-configured", true);

        CommandResult r = adapter.run(CMD, 1000);

        assertTrue(r.success());
        verify(files).exec("w-configured", CMD);
    }

    @Test
    @DisplayName("未配置工作空间时回退内置默认工作空间（按 builtin 标记，而非名称）")
    void fallsBackToBuiltinWorkspace() {
        FileService files = mock(FileService.class);
        when(files.exec("w-builtin", CMD)).thenReturn(Mono.just(
                ExecResult.success(FileOp.OpType.EXEC, "req-5").summary("exit=0").content("ok")));
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.listWorkspaces()).thenReturn(List.of(
                new Workspace().workspaceId("w-user"),
                new Workspace().workspaceId("w-builtin").builtin(true)));
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, manager, null, true);

        CommandResult r = adapter.run(CMD, 1000);

        assertTrue(r.success());
        verify(files).exec("w-builtin", CMD);
    }

    @Test
    @DisplayName("既无配置又无内置工作空间时拦截，不抛异常")
    void noWorkspaceBlocks() {
        FileService files = mock(FileService.class);
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.listWorkspaces()).thenReturn(List.of());
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, manager, null, true);

        CommandResult r = adapter.run(CMD, 1000);

        assertEquals(-1, r.exitCode());
        assertTrue(r.stderr().contains("无可用工作空间"), r.stderr());
        verify(files, never()).exec(anyString(), anyString());
    }

    @Test
    @DisplayName("执行臂阻塞超时标记 timedOut，且不得抛异常穿透到节点执行器")
    void blockingTimeoutMarksTimedOut() {
        FileService files = mock(FileService.class);
        when(files.exec("w1", CMD)).thenReturn(Mono.never());
        WorkflowArmSandboxAdapter adapter = new WorkflowArmSandboxAdapter(files, workspaceManager("w1", true), null, true);

        CommandResult r = adapter.run(CMD, 50);

        assertEquals(-1, r.exitCode());
        assertTrue(r.timedOut());
        assertFalse(r.success());
    }

    private static WorkspaceManager workspaceManager(String workspaceId, boolean builtin) {
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.listWorkspaces()).thenReturn(List.of(
                new Workspace().workspaceId(workspaceId).builtin(builtin)));
        return manager;
    }
}