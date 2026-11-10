package com.lucky.agent.web.controller;

import com.lucky.agent.workspace.api.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 工作空间创建的入参契约（台账 #43）：
 * {@code path} 缺失/为空时此前 {@code Path.of(null)} 直接 NPE → 裸 500，
 * 前端只看到「服务器错误」，没有任何可行动的提示。必须以 400 语义的
 * {@code IllegalArgumentException}（{@code GlobalExceptionHandler} 已映射）返回。
 */
class WorkspaceControllerCreateTest {

    private final WorkspaceManager manager = mock(WorkspaceManager.class);
    private final WorkspaceController controller = new WorkspaceController(manager);

    @Test
    @DisplayName("#43 path 为 null 不得 NPE/500，必须返回可读的 400 语义异常")
    void nullPathIsRejectedWithReadableMessage() {
        assertThatThrownBy(() -> controller.create(new WorkspaceController.CreateRequest("x", null, "FULL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        verifyNoInteractions(manager);
    }

    @Test
    @DisplayName("path 为空白同样拒绝")
    void blankPathIsRejected() {
        assertThatCode(() -> controller.create(new WorkspaceController.CreateRequest("x", "   ", "FULL")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(manager);
    }
}
