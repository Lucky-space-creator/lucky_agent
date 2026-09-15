package com.lucky.agent.web.stream;

import com.lucky.agent.common.contract.AgentChannel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.core.service.ConversationManager;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebChannel（SSE 适配）：Web 消费 {@code Flux<AgentEvent>}，只负责渲染与输入，不持有业务逻辑。
 */
@Service
public class WebChannel implements AgentChannel {

    private final ConversationManager conversationManager;
    private final ConversationStateManager stateManager;

    public WebChannel(ConversationManager conversationManager, ConversationStateManager stateManager) {
        this.conversationManager = conversationManager;
        this.stateManager = stateManager;
    }

    @Override
    public Flux<AgentEvent> subscribe(SessionRef session) {
        return stateManager.publisher().stream(session.sessionId());
    }

    @Override
    public Mono<RunResult> submit(SessionRef session, UserInput input) {
        return conversationManager.submit(session, input);
    }

    @Override
    public Mono<Void> cancel(SessionRef session) {
        return Mono.fromRunnable(() -> {
            // 置位会话取消标志：编排/引擎在循环边界检查并尽早退出，
            // 不再开启新轮次/新工具调用（此前仅移除 sink，执行线程仍在跑）
            stateManager.find(session.sessionId()).ifPresent(s -> s.requestCancel());
            // 先向仍连接的 SSE 客户端发一个终止事件：前端收到 stop 即关闭流、
            // 不会因连接被服务器端完成而自动重连（重连会立即收到引擎后续增量，表现成
            // 「点了停止还在不断发消息」）；随后才移除 sink 收尾。
            AgentEventPublisher publisher = stateManager.publisher();
            publisher.publish(session.sessionId(),
                    AgentEvent.stop(session.sessionId(), "cancelled", "已取消，停止本轮执行。"));
            publisher.remove(session.sessionId());
        });
    }

    @Override
    public Mono<SessionSnapshot> snapshot(SessionRef session) {
        return Mono.just(stateManager.snapshot(session));
    }
}
