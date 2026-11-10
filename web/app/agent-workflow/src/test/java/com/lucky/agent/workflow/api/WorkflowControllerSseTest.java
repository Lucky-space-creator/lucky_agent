package com.lucky.agent.workflow.api;

import com.lucky.agent.workflow.event.WorkflowEvent;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SSE 事件流的终止与投递契约（台账 #36）。
 *
 * <p>缺陷原状：{@code tryEmitNext} 返回值被丢弃（慢客户端溢出/连接终止后的事件静默丢失，
 * 无任何痕迹），且事件流<b>永不收口</b>——心跳 {@code Flux.interval} 是无限源，
 * {@code mergeWith} 之后即使实例已终态，流也不会 complete，前端 {@code EventSource}
 * 只能靠刷新页面收场。</p>
 */
class WorkflowControllerSseTest {

    private static final String INSTANCE = "inst-sse";
    private static final String OTHER = "inst-other";

    private static WorkflowEvent event(String instanceId, WorkflowEventType type) {
        return WorkflowEvent.of(instanceId, "wf-demo", null, type, type.name());
    }

    @Test
    @Timeout(10)
    @DisplayName("#36 终态事件放行给订阅者后整条流必须收口，并注销事件总线订阅")
    void terminalEventCompletesStreamAndUnsubscribes() throws Exception {
        WorkflowEventBus bus = new WorkflowEventBus();
        WorkflowController controller =
                new WorkflowController(mock(WorkflowService.class), bus);

        Flux<ServerSentEvent<WorkflowEvent>> stream = controller.events(INSTANCE);
        List<ServerSentEvent<WorkflowEvent>> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        stream.subscribe(received::add, err -> { /* 契约：不出错 */ }, done::countDown);

        bus.publish(event(OTHER, WorkflowEventType.NODE_STARTED));   // 别的实例：不得混入
        bus.publish(event(INSTANCE, WorkflowEventType.NODE_STARTED));
        bus.publish(event(INSTANCE, WorkflowEventType.WORKFLOW_FAILED));

        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("WORKFLOW_FAILED 之后事件流必须 complete（此前永不结束）")
                .isTrue();
        assertThat(received).hasSize(2);
        assertThat(received.get(1).event()).isEqualTo(WorkflowEventType.WORKFLOW_FAILED.name());
        assertThat(bus.subscriberCount())
                .as("收口后必须从事件总线注销，否则监听器随连接数泄漏")
                .isZero();
    }

    @Test
    @Timeout(10)
    @DisplayName("#36 WORKFLOW_COMPLETED 同样是终态：流收口且终态事件先送达")
    void completedEventAlsoTerminates() throws Exception {
        WorkflowEventBus bus = new WorkflowEventBus();
        WorkflowController controller =
                new WorkflowController(mock(WorkflowService.class), bus);

        CountDownLatch done = new CountDownLatch(1);
        List<ServerSentEvent<WorkflowEvent>> received = new CopyOnWriteArrayList<>();
        controller.events(INSTANCE).subscribe(received::add, err -> { /* 不出错 */ }, done::countDown);

        bus.publish(event(INSTANCE, WorkflowEventType.WORKFLOW_COMPLETED));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).event()).isEqualTo(WorkflowEventType.WORKFLOW_COMPLETED.name());
    }
}
