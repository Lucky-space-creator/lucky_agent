package executor.support.fileops;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.ExecutorClient;
import com.lucky.agent.executor.support.fileops.LocalFileService;
import com.lucky.agent.permission.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LocalFileService#list} 的异步契约（缺陷台账 #22）。
 *
 * <p>缺陷原状：所有方法在<b>组装 Mono</b> 时就同步跑完权限裁决 + 执行臂往返，调用方拿到的其实是
 * 「已经跑完的结果」。JLine 的补全回调是同步的，于是大目录首次 Tab 直接卡输入线程，
 * 外层 {@code subscribeOn} 挪不走已经发生的读盘。本用例守住两条底线：
 * 组装阶段零副作用、订阅后在工作线程上执行。</p>
 */
class LocalFileServiceListAsyncTest {

    /** 记录调用次数与执行线程的执行臂桩。 */
    private static final class RecordingClient implements ExecutorClient {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> thread = new AtomicReference<>();
        private final boolean fail;

        RecordingClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Mono<ExecResult> execute(FileOp op, PermissionDecision decision) {
            calls.incrementAndGet();
            thread.set(Thread.currentThread().getName());
            return fail ? Mono.error(new IllegalStateException("boom")) : Mono.just(new ExecResult().ok(true));
        }

        @Override
        public Mono<Boolean> ping() {
            return Mono.just(true);
        }
    }

    private static PermissionService allowAll() {
        PermissionService permission = mock(PermissionService.class);
        when(permission.evaluateFileOp(any(), anyString())).thenReturn(PermissionDecision.ALLOW);
        return permission;
    }

    @Test
    @DisplayName("组装阶段零副作用：不裁决、不打执行臂")
    void listDoesNothingBeforeSubscribe() {
        RecordingClient client = new RecordingClient(false);
        PermissionService permission = allowAll();
        LocalFileService service = new LocalFileService(client, permission);

        Mono<ExecResult> mono = service.list("ws-1", "");

        assertEquals(0, client.calls.get(), "组装 list() 不得触碰执行臂（否则同步补全回调必卡）");
        verifyNoInteractions(permission);

        ExecResult result = mono.block();
        assertTrue(result != null && result.ok());
        assertEquals(1, client.calls.get(), "订阅后才真正执行");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("执行发生在弹性线程池，不占用调用线程")
    void listRunsOffCallerThread() {
        RecordingClient client = new RecordingClient(false);
        LocalFileService service = new LocalFileService(client, allowAll());

        service.list("ws-1", "src").block();

        String worker = client.thread.get();
        assertNotEquals(Thread.currentThread().getName(), worker, "IO 不应跑在调用线程上");
        assertTrue(worker != null && worker.contains("boundedElastic"),
                "应跑在 boundedElastic 上，实际：" + worker);
    }

    @Test
    @DisplayName("执行期异常转为错误信号，不在组装阶段抛出")
    void listFailureSurfacesOnSubscribe() {
        LocalFileService service = new LocalFileService(new RecordingClient(true), allowAll());

        Mono<ExecResult> mono = service.list("ws-1", ""); // 组装阶段不得抛

        assertThrows(IllegalStateException.class, mono::block);
    }
}
