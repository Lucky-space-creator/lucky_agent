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
     * @param session 会话
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> stream(SessionRef session) {
        return webChannel.subscribe(session)
                .map(this::toSse)
                .mergeWith(Flux.interval(HEARTBEAT)
                        .map(i -> ServerSentEvent.<String>builder().comment("ping").build()));
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
