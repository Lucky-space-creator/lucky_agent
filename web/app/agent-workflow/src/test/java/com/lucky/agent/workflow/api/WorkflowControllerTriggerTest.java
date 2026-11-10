package com.lucky.agent.workflow.api;

import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流触发必须离开 Reactor 事件循环（台账 #20）。
 *
 * <p>WebFlux 在 {@code reactor-http-nio-N} 线程上调用控制器方法，而 {@code RunMode.SYNC}
 * 会在调用线程里同步跑完整条工作流，其中 CODE/EXEC 节点还要 {@code Mono.block(timeout)}
 * 等命令返回。在非阻塞线程上 {@code block()} 直接抛 {@code IllegalStateException}，
 * 被节点执行器吞成「超时」—— 现象是「CODE 节点每次都超时」，根因却在线程调度。</p>
 *
 * <p>本用例的判据是「执行线程不是订阅线程，且是 boundedElastic」：只要有人把
 * {@code subscribeOn} 去掉、或把返回类型改回裸对象，断言立即失败。</p>
 */
class WorkflowControllerTriggerTest {

    private static final String DEF_ID = "wf-trigger";

    @Test
    @DisplayName("#20 SYNC 触发必须在 boundedElastic 上执行，不得占用调用线程")
    void triggerRunsOnBoundedElastic() {
        WorkflowService service = mock(WorkflowService.class);
        WorkflowInstance instance = new WorkflowInstance(DEF_ID, "验收用工作流");
        AtomicReference<String> executedOn = new AtomicReference<>();
        when(service.trigger(eq(DEF_ID), any(), any())).thenAnswer(invocation -> {
            executedOn.set(Thread.currentThread().getName());
            return instance;
        });

        WorkflowController controller =
                new WorkflowController(service, mock(WorkflowEventBus.class));
        Mono<WorkflowInstance> result = controller.trigger(DEF_ID, null);

        assertThat(executedOn.get())
                .as("Mono 必须惰性：未订阅前不得开始跑工作流")
                .isNull();

        WorkflowInstance actual = result.block();

        assertThat(actual).isSameAs(instance);
        assertThat(executedOn.get())
                .as("执行线程就是订阅线程 → 等于仍在事件循环上同步跑完整条工作流")
                .isNotEqualTo(Thread.currentThread().getName());
        assertThat(executedOn.get())
                .as("必须显式下沉到允许阻塞的 boundedElastic")
                .contains("boundedElastic");
    }

    @Test
    @DisplayName("#20 触发失败（如模式取值非法）必须以 onError 传出，而不是同步抛出")
    void invalidModeSurfacesAsReactiveError() {
        WorkflowService service = mock(WorkflowService.class);
        when(service.trigger(eq(DEF_ID), any(), any()))
                .thenThrow(new IllegalArgumentException("执行模式取值非法: 'xxx'，允许值: SYNC / ASYNC"));

        WorkflowController controller =
                new WorkflowController(service, mock(WorkflowEventBus.class));
        Mono<WorkflowInstance> result = controller.trigger(DEF_ID, null);

        // 错误在订阅时才发生（subscribeOn 生效），由 reactor 包装成 onError 信号
        assertThat(result.onErrorResume(e -> Mono.empty()).block())
                .as("参数错误必须让前端收到 4xx/错误体，而不是静默成功")
                .isNull();
    }
}
