package com.lucky.agent.core.util.runtime;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.core.repository.SessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话状态唯一事实源（D19）。
 *
 * <p>引擎每次循环从本状态追加消息，避免多通道状态散落；所有状态查询走本管理器，
 * 通道不得自行缓存会话。会话历史为 LangChain4j ChatMessage 列表，兼容编排引擎循环输入。</p>
 *
 * <p><b>进程重启恢复（P1-2）</b>：消息持久化在磁盘（SessionRepository），新建状态时
 * 把持久化的 user/assistant 消息回灌进内存态，保证「重启后模型看到的上下文与用户看到的
 * 历史一致」。工具结果类消息不重建（磁盘仅存文本），避免恢复出孤儿 ToolResult。</p>
 */
@Slf4j
@Component
public class ConversationStateManager {

    private final AgentEventPublisher publisher;
    private final SessionRepository sessionRepository;
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ConversationStateManager(AgentEventPublisher publisher, SessionRepository sessionRepository) {
        this.publisher = publisher;
        this.sessionRepository = sessionRepository;
    }

    /** 获取（或创建）会话状态；新建时从磁盘回灌持久化历史（P1-2）。 */
    public SessionState session(SessionRef ref) {
        return sessions.computeIfAbsent(ref.sessionId(), k -> {
            SessionState state = new SessionState(ref);
            restoreFromPersistence(state, ref.sessionId());
            return state;
        });
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

    /** 从磁盘持久化消息回灌新建的内存态（P1-2：重启后上下文不丢失）。 */
    private void restoreFromPersistence(SessionState state, String sessionId) {
        try {
            List<SessionRepository.ReplayMessage> records = sessionRepository.loadForReplay(sessionId);
            if (records == null || records.isEmpty()) {
                return;
            }
            int restoredThinking = 0;
            for (SessionRepository.ReplayMessage r : records) {
                String content = r.content() == null ? "" : r.content();
                String role = r.role() == null ? "" : r.role();
                if ("user".equals(role)) {
                    state.appendMessage(UserMessage.from(content));
                } else if ("assistant".equals(role)) {
                    // 恢复思考链：推理模型在「请求携带 tools」时要求历史所有轮的
                    // reasoning_content 原样回传，仅用 AiMessage.from(content) 重建会丢掉思考链，
                    // 导致带工具重放时被模型侧拒绝（HTTP 400 must be passed back）。
                    String thinking = r.thinking();
                    if (thinking != null && !thinking.isBlank()) {
                        restoredThinking++;
                        state.appendMessage(AiMessage.builder().text(content).thinking(thinking).build());
                    } else {
                        state.appendMessage(AiMessage.from(content));
                    }
                }
                // 其余角色（observation/tool 等）不重建：磁盘仅持久化 user/assistant 文本
            }
            log.info("进程重启恢复会话上下文：session={} 回灌 {} 条（含思考链 {} 条）",
                    sessionId, state.messages().size(), restoredThinking);
        } catch (Exception e) {
            log.warn("会话上下文回灌失败（以空上下文继续）：session={} err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 会话运行时状态。
     */
    public static class SessionState {
        private final SessionRef ref;
        private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
        /** 会话内已注入的记忆条目锚点集合（预取去重，readFileState 等价物）。 */
        private final Set<String> prefetchedIds = ConcurrentHashMap.newKeySet();
        private volatile Phase phase;
        private volatile boolean running;
        /** 用户已请求取消本轮运行（软取消：不再开启新轮次/新工具调用，正在进行的单次调用放行）。 */
        private volatile boolean cancelRequested;

        /**
         * @param ref 会话引用
         */
        public SessionState(SessionRef ref) {
            this.ref = ref;
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

        /** 请求取消当前会话运行（置位后编排/引擎在循环边界检查并尽早退出）。 */
        public void requestCancel() {
            this.cancelRequested = true;
        }

        /** 会话是否已被请求取消。 */
        public boolean cancelRequested() {
            return cancelRequested;
        }

        /** 新一轮提交时清除取消标记，允许会话继续运行。 */
        public void clearCancel() {
            this.cancelRequested = false;
        }

        /**
         * 标记一批条目为「已注入本会话」，返回其中<b>首次</b>注入的条目子集。
         * 已在集合中的条目不再返回，用于同会话内预取去重。
         *
         * @param ids 候选锚点 id
         * @return 首次注入的 id 集合（可为空）
         */
        public Set<String> markPrefetched(java.util.Collection<String> ids) {
            Set<String> fresh = new java.util.HashSet<>();
            if (ids == null) {
                return fresh;
            }
            for (String id : ids) {
                if (prefetchedIds.add(id)) {
                    fresh.add(id);
                }
            }
            return fresh;
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
                    messages.removeLast();
                }
            }
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
