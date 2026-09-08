package com.lucky.agent.web.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Flux&lt;AgentEvent&gt; → SSE 适配。
 *
 * <p>长任务不阻塞：Project Reactor + SSE，内核线程不被长推理占死；Web 容器业务线程异步返回后立即释放。
 * 周期性发送心跳注释，维持连接与代理 keep-alive。</p>
 */
@Component
public class ReactiveSsePublisher {

    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final WebChannel webChannel;
    private final ObjectMapper objectMapper;

    public ReactiveSsePublisher(WebChannel webChannel, ObjectMapper objectMapper) {
        this.webChannel = webChannel;
        this.objectMapper = objectMapper;
    }

    /**
     * 订阅会话事件流并序列化为 SSE。
     *
     * <p>心跳（注释帧）仅用于维持代理 keep-alive；主事件流结束时（stop/error/complete）心跳随即
     * 终止，SSE 连接随之关闭，避免被无限心跳永久悬挂造成连接泄漏。</p>
     *
     * @param session 会话
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> stream(SessionRef session) {
        // share()：事件流是冷源（defer 包裹，订阅时才建 sink/补发暂存），若直接复用会触发两次订阅、
        // 让暂存被错误订阅消费且 merge 永不结束；share 后只有一次底层订阅，心跳只观察完成信号。
        Flux<ServerSentEvent<String>> events = webChannel.subscribe(session).map(this::toSse).share();
        // 主事件流一结束（onComplete/onError）即终止心跳；merge 需两源都结束才完成，故用 takeUntilOther 兜底
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(HEARTBEAT)
                .takeUntilOther(events.ignoreElements())
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        return events.mergeWith(heartbeats);
    }

    private ServerSentEvent<String> toSse(AgentEvent event) {
        try {
            return ServerSentEvent.<String>builder()
                    .id(event.eventId())
                    .event(event.type())
                    .data(objectMapper.writeValueAsString(event))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("error")
                    .data("{\"type\":\"error\",\"msg\":\"序列化事件失败\"}").build();
        }
    }
}
