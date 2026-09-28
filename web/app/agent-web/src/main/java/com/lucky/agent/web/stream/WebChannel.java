package com.lucky.agent.web.stream;

import com.lucky.agent.common.contract.AgentChannel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.core.util.runtime.SessionCancellation;
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

    /**
     * 取消当前运行：与 CLI 通道共用同一内核原语 {@link SessionCancellation#cancel}
     * （置取消标志 → 补发 {@code stop} 收尾 → 销毁 sink），通道不各自实现。
     */
    @Override
    public Mono<Void> cancel(SessionRef session) {
        return Mono.fromRunnable(() -> SessionCancellation.cancel(stateManager, session));
    }

    @Override
    public Mono<SessionSnapshot> snapshot(SessionRef session) {
        return Mono.just(stateManager.snapshot(session));
    }
}
