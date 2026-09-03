package com.lucky.agent.core.runtime;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.core.config.CoreProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话状态唯一事实源（D19）。
 *
 * <p>引擎每次循环从本状态追加消息，避免多通道状态散落；所有状态查询走本管理器，
 * 通道不得自行缓存会话。会话历史为 LangChain4j ChatMessage 列表，兼容编排引擎循环输入。</p>
 */
@Component
public class ConversationStateManager {

    private final AgentEventPublisher publisher;
    private final CoreProperties properties;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ConversationStateManager(AgentEventPublisher publisher, CoreProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    /** 获取（或创建）会话状态。 */
    public SessionState session(SessionRef ref) {
        return sessions.computeIfAbsent(ref.sessionId(), k -> new SessionState(ref, properties));
    }

    /** 查找会话状态。 */
    public Optional<SessionState> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 事件发布器（供引擎推送事件流）。 */
    public AgentEventPublisher publisher() {
        return publisher;
    }

    /** 会话快照（通道只读视图）。 */
    public SessionSnapshot snapshot(SessionRef ref) {
        SessionState state = sessions.get(ref.sessionId());
        SessionSnapshot snap = new SessionSnapshot(ref.sessionId(), ref.userId(), ref.workspaceId());
        if (state != null) {
            List<SessionSnapshot.MessageRecord> records = state.messages().stream()
                    .map(m -> new SessionSnapshot.MessageRecord(
                            m.type() == null ? "unknown" : m.type().name().toLowerCase(),
                            m instanceof UserMessage ? ((UserMessage) m).singleText()
                                    : (m instanceof AiMessage ? ((AiMessage) m).text() : ""),
                            null))
                    .toList();
            snap.messages(records);
            snap.state(Map.of(
                    "phase", state.phase() == null ? null : state.phase().name(),
                    "running", state.running()));
        }
        return snap;
    }

    /** 销毁会话（清状态与事件流）。 */
    public void destroy(String sessionId) {
        sessions.remove(sessionId);
        publisher.remove(sessionId);
    }

    /**
     * 会话运行时状态。
     */
    public static class SessionState {
        private final SessionRef ref;
        private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
        private final RunBudget budget;
        private volatile Phase phase;
        private volatile boolean running;

        /**
         * @param ref        会话引用
         * @param properties 核心配置（安全阀 maxTurns / maxBudget 来源，避免硬编码）
         */
        public SessionState(SessionRef ref, CoreProperties properties) {
            this.ref = ref;
            this.budget = properties == null
                    ? new RunBudget(30, -1)
                    : new RunBudget(properties.runMaxTurns(), properties.runMaxBudget());
        }

        public SessionRef ref() {
            return ref;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        public void appendMessage(ChatMessage msg) {
            if (msg != null) {
                messages.add(msg);
            }
        }

        /** 整体替换历史消息（上下文压缩后回写，保证状态唯一事实源）。 */
        public void replaceMessages(List<ChatMessage> replaced) {
            if (replaced == null) {
                return;
            }
            synchronized (messages) {
                messages.clear();
                messages.addAll(replaced);
            }
        }

        /** 回滚到指定长度（子任务局部重试前清除失败尝试追加的消息，避免污染状态）。 */
        public void truncateTo(int size) {
            if (size < 0) {
                return;
            }
            synchronized (messages) {
                while (messages.size() > size) {
                    messages.remove(messages.size() - 1);
                }
            }
        }

        public RunBudget budget() {
            return budget;
        }

        public Phase phase() {
            return phase;
        }

        public void phase(Phase phase) {
            this.phase = phase;
        }

        public boolean running() {
            return running;
        }

        public void running(boolean running) {
            this.running = running;
        }
    }
}
