package com.lucky.agent.web.adapter;

import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workflow.adapter.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 命令沙箱在 Reactor 非阻塞线程上必须明确拒绝，而不是抛一个看不懂的异常（台账 #20）。
 *
 * <p>适配器的契约是同步的（下游 {@code NodeExecutor} 要一个 {@link CommandResult}），
 * 而 Reactor 的非阻塞线程上调用 {@code block()} 会抛
 * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not supported...}。
 * 修复前这个异常被 goto 到「等待超时或异常」分支，用户看到的是「CODE 节点每次都超时」，
 * 于是去查命令、查沙箱开关，查不到根因。现在提前拦下并说明该怎么修。</p>
 */
class WorkflowArmSandboxAdapterNonBlockingTest {

    @Test
    @DisplayName("#20 非阻塞线程上同步等待命令 → 必须拦下并给出可行动的定位信息")
    void refusesToBlockOnNonBlockingThread() throws Exception {
        FileService fileService = mock(FileService.class);
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        WorkflowArmSandboxAdapter adapter =
                new WorkflowArmSandboxAdapter(fileService, workspaceManager, null, true);

        AtomicReference<CommandResult> captured = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // Schedulers.parallel() 的线程带 Reactor 的 NonBlocking 标记，正是 WebFlux 事件循环的同类
        Schedulers.parallel().schedule(() -> {
            captured.set(adapter.run("echo hi", 1000));
            done.countDown();
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        CommandResult result = captured.get();
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.stderr())
                .as("必须点名「非阻塞线程」与 boundedElastic，否则用户只会看到『超时』")
                .contains("非阻塞线程")
                .contains("boundedElastic");
        verifyNoInteractions(fileService);
    }

    @Test
    @DisplayName("#20 正常线程不得误拦（守卫只在非阻塞线程上生效）")
    void normalThreadIsNotBlockedByGuard() {
        FileService fileService = mock(FileService.class);
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        WorkflowArmSandboxAdapter adapter =
                new WorkflowArmSandboxAdapter(fileService, workspaceManager, null, true);

        // 没有内置工作空间 → 走既有「无可用工作空间」分支，而不是新加的守卫
        CommandResult result = adapter.run("echo hi", 1000);

        assertThat(result.success()).isFalse();
        assertThat(result.stderr())
                .as("普通线程上守卫必须放行，否则等于把命令沙箱整个关掉")
                .doesNotContain("非阻塞线程")
                .contains("工作空间");
    }
}
