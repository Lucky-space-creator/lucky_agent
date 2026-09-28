package cli.input;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.input.ShellPrefix;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code !} 直执行的审批收口（P1-4 回归）。
 *
 * <p>{@code !} 命令曾有一条独立于 {@link ApprovalHandler} 的自建 y/N 提示 —— 那是第二条审批路径，
 * 语义与 Agent 触发的确认不一致。本测试锁定：命中 ASK 一律经 {@link ApprovalHandler}，
 * 同意后按<b>与执行臂同形</b>的 {@link FileOp} 放行并重试一次，拒绝则不放行、不重试。</p>
 */
class ShellPrefixTest {

    private static final String WS = "ws-1";

    private final OutputSink out = OutputSink.of(
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), Theme.plain());

    @Test
    @DisplayName("命中 ASK 时收口到 ApprovalHandler：同意后 allowOnce 放行并重试一次")
    void askRoutesThroughApprovalHandler() {
        FileService files = mock(FileService.class);
        when(files.exec(WS, "git push")).thenReturn(
                Mono.just(ExecResult.failure(FileOp.OpType.EXEC, null, ExecResult.ASK_REQUIRED + "：需要确认")),
                Mono.just(ExecResult.success(FileOp.OpType.EXEC, null)));
        PermissionService perms = mock(PermissionService.class);
        // 自动放行的裁决器，等价于用户在四档里选了「仅本次」
        ApprovalHandler approval = new ApprovalHandler(out, null, null, null, true);

        assertTrue(ShellPrefix.run("!git push", WS, files, approval, perms, out));

        ArgumentCaptor<FileOp> captor = ArgumentCaptor.forClass(FileOp.class);
        verify(perms, times(1)).allowOnce(captor.capture());
        assertEquals(FileOp.OpType.EXEC, captor.getValue().opType(), "放行形状必须是 EXEC");
        assertEquals("git push", captor.getValue().args().get("command"), "命令必须原样放行");
        verify(files, times(2)).exec(WS, "git push");
    }

    @Test
    @DisplayName("拒绝授权则不放行、不重试（不再存在绕过审批的第二条路径）")
    void denyDoesNotRetryOrAllow() {
        FileService files = mock(FileService.class);
        when(files.exec(WS, "rm -rf /")).thenReturn(
                Mono.just(ExecResult.failure(FileOp.OpType.EXEC, null, ExecResult.ASK_REQUIRED)));
        PermissionService perms = mock(PermissionService.class);
        // 非交互 + 未开 --auto-approve → 直接拒绝
        ApprovalHandler approval = new ApprovalHandler(out, null, null, null, false);

        ShellPrefix.run("!rm -rf /", WS, files, approval, perms, out);

        verify(perms, never()).allowOnce(any());
        verify(files, times(1)).exec(WS, "rm -rf /");
    }
}