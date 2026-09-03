package com.lucky.agent.common.contract;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通道契约（契约 §5），Web 与 CLI 复用。
 *
 * <p>通道只负责渲染和输入，不持有业务逻辑；所有状态查询走 ConversationStateManager，
 * 通道不得自行缓存会话。Web 使用 SSE 适配，CLI 使用终端渲染适配，两者消费同一 {@link Flux} 事件流。</p>
 */
@Remote(serviceName = "channel")
public interface AgentChannel {

    /** 订阅会话事件流。 */
    Flux<AgentEvent> subscribe(SessionRef session);

    /** 提交用户输入，内核运行并返回结果。 */
    Mono<RunResult> submit(SessionRef session, UserInput input);

    /** 取消会话运行。 */
    Mono<Void> cancel(SessionRef session);

    /** 查询会话快照。 */
    Mono<SessionSnapshot> snapshot(SessionRef session);
}
