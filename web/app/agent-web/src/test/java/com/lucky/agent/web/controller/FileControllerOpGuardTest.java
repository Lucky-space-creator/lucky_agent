package com.lucky.agent.web.controller;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.service.FileOpForwarder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /api/files/op} 的操作白名单。
 *
 * <p>文件接口没有任何「待确认」交互通道，Web 层也从不发起命令执行。
 * 若不设白名单，本机任意网页/脚本只要知道 workspaceId 就能借这个端点
 * 发起 EXEC（只需工作空间档位为 FULL），等于绕开会话/工作流的确认链路。</p>
 */
class FileControllerOpGuardTest {

    private final FileOpForwarder forwarder = mock(FileOpForwarder.class);
    private final FileController controller = new FileController(forwarder);

    @Test
    @DisplayName("EXEC 不在文件接口开放范围：直接拒绝且不转发到执行臂")
    void execIsRejected() {
        FileOp op = FileOp.of(FileOp.OpType.EXEC, "ws-1", "")
                .args(Map.of("command", "rm -rf /"));

        ExecResult res = controller.op(op).block();

        assertThat(res).isNotNull();
        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("不开放");
        verify(forwarder, never()).forward(any());
    }

    @Test
    @DisplayName("READ/WRITE/文件管理类操作正常转发")
    void fileOpsAreForwarded() {
        ExecResult ok = new ExecResult().ok(true).opType(FileOp.OpType.READ);
        when(forwarder.forward(any())).thenReturn(reactor.core.publisher.Mono.just(ok));

        assertThat(controller.op(FileOp.of(FileOp.OpType.READ, "ws-1", "a.txt")).block().ok()).isTrue();
        assertThat(controller.op(FileOp.of(FileOp.OpType.WRITE, "ws-1", "a.txt")).block().ok()).isTrue();
        assertThat(controller.op(FileOp.of(FileOp.OpType.DELETE, "ws-1", "a.txt")).block().ok()).isTrue();
        assertThat(controller.op(FileOp.of(FileOp.OpType.MKDIR, "ws-1", "d")).block().ok()).isTrue();
    }

    @Test
    @DisplayName("缺少 opType 时返回可读失败，不得 NPE")
    void nullOpTypeIsRejected() {
        ExecResult res = controller.op(FileOp.of(null, "ws-1", "a.txt")).block();

        assertThat(res).isNotNull();
        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("opType");
        verify(forwarder, never()).forward(any());
    }
}
