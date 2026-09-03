package com.lucky.agent.core.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 五级压缩流水线实现（D16 / §4.13）。
 *
 * <p>组合 Microcompact（裁剪）→ Snip（滑动窗口）→ Reactive（摘要下沉），全程由
 * {@link PreservedSegment} 保全系统提示/最近 N 轮/关键结果；压缩连续失败由
 * {@link TokenCircuitBreaker} 熔断，转兜底（返回原消息，不无限重试）。</p>
 */
public class FiveLevelCompactionPipeline implements CompactionPipeline {

    private final MicrocompactStrategy microcompact;
    private final SnipStrategy snip;
    private final ReactiveCompactStrategy reactive;
    private final PreservedSegment preserved;
    private final TokenCircuitBreaker breaker;
    private final int keepRecentTurns;

    public FiveLevelCompactionPipeline(int maxToolResultLength, int keepRecentTurns,
                                       int summaryLength, int failureThreshold) {
        this.microcompact = new MicrocompactStrategy(maxToolResultLength);
        this.snip = new SnipStrategy(keepRecentTurns);
        this.reactive = new ReactiveCompactStrategy(summaryLength);
        this.preserved = new PreservedSegment(keepRecentTurns);
        this.breaker = new TokenCircuitBreaker(failureThreshold);
        this.keepRecentTurns = keepRecentTurns;
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> messages, Map<String, Object> context) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        // 熔断打开 → 兜底：返回原消息，不再重试压缩
        if (breaker.isOpen()) {
            return messages;
        }
        try {
            List<ChatMessage> step1 = microcompact.apply(messages, context);
            List<ChatMessage> step2 = snip.apply(step1, context);
            List<ChatMessage> step3 = reactive.apply(step2, context);
            // 保全校验：确保 preservedSegment 未被丢弃
            List<ChatMessage> finalMsg = ensurePreserved(step3, messages);
            breaker.onSuccess();
            return finalMsg;
        } catch (Exception e) {
            breaker.onFailure();
            // 降级：返回原消息，由模型兜底流程接管
            return messages;
        }
    }

    /** 保全段校验：若压缩后丢失了应保全的消息，从原消息补回。 */
    private List<ChatMessage> ensurePreserved(List<ChatMessage> compacted, List<ChatMessage> original) {
        int from = preserved.recentFromIndex(original);
        List<ChatMessage> result = new ArrayList<>(compacted);
        for (int i = 0; i < original.size(); i++) {
            ChatMessage om = original.get(i);
            if (preserved.shouldPreserve(om, i >= from) && !result.contains(om)) {
                result.add(om);
            }
        }
        return result;
    }

    public TokenCircuitBreaker breaker() {
        return breaker;
    }
}
