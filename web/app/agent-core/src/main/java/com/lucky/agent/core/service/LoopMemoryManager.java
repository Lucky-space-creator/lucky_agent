package com.lucky.agent.core.service;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.HookEvent;
import com.lucky.agent.common.dto.HookEventName;
import com.lucky.agent.core.compact.CompactionPipeline;
import com.lucky.agent.core.compact.TokenMeter;
import com.lucky.agent.core.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主回环记忆管理（流程图 K 节点）。
 *
 * <p>改造前 K 节点是空实现：只推送一条 thought 并替换 goal，不触发任何压缩/记忆动作，
 * 导致「未达成 → 回 B 再分析」时上下文只增不减。本类把 K 节点真正接线为两步：</p>
 * <ol>
 *   <li><b>短期记忆（上下文压缩）</b>：按占用率阈值触发 {@link CompactionPipeline}，
 *       前后触发 {@code PreCompact} / {@code PostCompact} Hook（deny-wins 可阻断压缩）；</li>
 *   <li><b>长期记忆沉淀</b>：把本轮执行结论以 {@code observation} 轨迹写入用户记忆轨，
 *       置信度低于用户原话，供后续轮次召回。</li>
 * </ol>
 */
@Slf4j
@Component
public class LoopMemoryManager {

    /** 单轮沉淀到长期记忆的最大文本长度（避免把整段执行日志灌进记忆）。 */
    private static final int MEMORY_MAX_LENGTH = 1500;

    private final CompactionPipeline compactionPipeline;
    private final TokenMeter tokenMeter;
    private final LifecycleHookDispatcher hookDispatcher;
    private final MemoryStore memoryStore;
    private final ModelRouter modelRouter;
    private final double contextThreshold;

    public LoopMemoryManager(CompactionPipeline compactionPipeline,
                             TokenMeter tokenMeter,
                             LifecycleHookDispatcher hookDispatcher,
                             @Qualifier("userMemoryStore") MemoryStore memoryStore,
                             ModelRouter modelRouter,
                             @Value("${model.context-threshold:0.9}") double contextThreshold) {
        this.compactionPipeline = compactionPipeline;
        this.tokenMeter = tokenMeter;
        this.hookDispatcher = hookDispatcher;
        this.memoryStore = memoryStore;
        this.modelRouter = modelRouter;
        this.contextThreshold = contextThreshold;
    }

    /**
     * 执行一次记忆管理（每轮主回环结束、回到 B 节点前调用）。
     *
     * @param ctx       会话上下文
     * @param state     会话状态
     * @param roundLog  本轮执行/验证日志（沉淀到长期记忆）
     * @param publisher 事件发布器
     */
    public void manage(ConversationCtx ctx, ConversationStateManager.SessionState state,
                       String roundLog, AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();

        // ① 长期记忆：本轮结论沉淀（置信度低于用户原话，避免过程噪音污染记忆）
        if (roundLog != null && !roundLog.isBlank()) {
            try {
                memoryStore.appendUser(ctx.userId(), truncate(roundLog, MEMORY_MAX_LENGTH), 0.6, "round");
            } catch (Exception e) {
                log.warn("长期记忆沉淀失败：session={}", sessionId, e);
            }
        }

        // ② 短期记忆：上下文压缩（阈值触发 + Hook 可阻断）
        List<ChatMessage> messages = new ArrayList<>(state.messages());
        if (messages.isEmpty()) {
            return;
        }
        int window = modelRouter.contextWindow(modelIdOf(ctx));
        if (window <= 0) {
            return;
        }
        long used = tokenMeter.count(messages);
        long budget = (long) (window * contextThreshold);
        if (used < budget) {
            return;
        }

        HookEvent pre = hookDispatcher.dispatch(new HookEvent(HookEventName.PRE_COMPACT, sessionId));
        if (pre != null && pre.isDenied()) {
            publisher.publish(sessionId, AgentEvent.thought(sessionId,
                    "【记忆管理】压缩被 Hook 阻断：" + pre.decisionReason()));
            return;
        }
        try {
            List<ChatMessage> compacted = compactionPipeline.compact(messages,
                    Map.of("threshold", contextThreshold));
            if (compacted == null || compacted.isEmpty() || compacted.size() >= messages.size()) {
                hookDispatcher.fire(new HookEvent(HookEventName.POST_COMPACT, sessionId));
                return;
            }
            state.replaceMessages(compacted);
            long after = tokenMeter.count(compacted);
            publisher.publish(sessionId, AgentEvent.thought(sessionId,
                    "【记忆管理】上下文压缩完成：" + messages.size() + " → " + compacted.size()
                            + " 条，" + used + " → " + after + " tokens（保留系统提示与关键结果）。"));
            hookDispatcher.fire(new HookEvent(HookEventName.POST_COMPACT, sessionId));
        } catch (Exception e) {
            log.warn("上下文压缩失败，保留原上下文：session={}", sessionId, e);
        }
    }

    private String modelIdOf(ConversationCtx ctx) {
        if (ctx == null || ctx.extra() == null) {
            return null;
        }
        Object raw = ctx.extra().get("modelId");
        return raw == null ? null : String.valueOf(raw);
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
