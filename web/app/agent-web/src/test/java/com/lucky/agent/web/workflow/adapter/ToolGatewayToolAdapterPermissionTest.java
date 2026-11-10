package com.lucky.agent.web.workflow.adapter;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流 TOOL 节点必须继承工作空间权限档（台账 #13）。
 *
 * <p>修复前上下文硬编码 {@code PermissionLevel.FULL}：在「只读/修改」档工作空间里，
 * 对话通道写文件会被拦，而工作流的 TOOL 节点却以 FULL 身份放行 —— 权限模型被旁路。
 * 本测试捕获实际下发给 {@link ToolGateway} 的上下文，断言档位来自工作空间配置。</p>
 */
class ToolGatewayToolAdapterPermissionTest {

    @Test
    void toolNodeInheritsWorkspacePermissionLevel() {
        PermissionLevel level = PermissionLevel.READ_ONLY;
        AtomicReference<ConversationCtx> captured = new AtomicReference<>();

        ToolGateway gateway = mock(ToolGateway.class);
        when(gateway.dispatch(anyString(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(2));
            return ToolResult.ok(Map.of("content", "ok"));
        });

        Workspace ws = Workspace.of("ws-1", "只读空间", "/tmp/ws-1", level, Instant.now());
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.listWorkspaces()).thenReturn(List.of(ws));
        when(manager.findById("ws-1")).thenReturn(Optional.of(ws));

        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);

        ToolGatewayToolAdapter adapter = new ToolGatewayToolAdapter(gateway, provider);
        Map<String, Object> result = adapter.call("file_write", Map.of("path", "a.txt", "content", "x"));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().permissionLevel())
                .as("必须继承工作空间档位，而不是硬编码 FULL")
                .isEqualTo(level);
        assertThat(result).containsEntry("ok", true);
    }
}
