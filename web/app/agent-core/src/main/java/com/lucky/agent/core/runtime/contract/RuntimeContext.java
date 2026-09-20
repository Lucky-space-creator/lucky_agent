package com.lucky.agent.core.runtime.contract;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.runtime.budget.BudgetManager;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时上下文：贯穿一次编排的全部运行时依赖与可变状态。
 * <p>主循环与工具、中间件、策略共享同一份上下文，避免在方法间传递大量参数（状态收敛点）。</p>
 */
public class RuntimeContext {

    private final SessionRef ref;
    private final ConversationCtx conversation;
    private final AgentEventPublisher publisher;
    private final BudgetManager budget;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile TraceContext trace;

    public RuntimeContext(SessionRef ref, ConversationCtx conversation,
                          AgentEventPublisher publisher, TraceContext trace, BudgetManager budget) {
        this.ref = ref;
        this.conversation = conversation;
        this.publisher = publisher;
        this.trace = trace;
        this.budget = budget;
    }

    public SessionRef ref() {
        return ref;
    }

    public ConversationCtx conversation() {
        return conversation;
    }

    public AgentEventPublisher publisher() {
        return publisher;
    }

    public BudgetManager budget() {
        return budget;
    }

    public TraceContext trace() {
        return trace;
    }

    public void trace(TraceContext trace) {
        this.trace = trace;
    }

    public String sessionId() {
        return ref.sessionId();
    }

    public String workspaceId() {
        return ref.workspaceId();
    }

    public String goal() {
        return conversation.goal();
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    public void attribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }
}
