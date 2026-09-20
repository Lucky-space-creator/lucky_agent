package com.lucky.agent.core.util.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 压缩策略（D16 五级流水线之一）。
 *
 * <p>每个策略处理一类压缩动作：Microcompact（工具结果裁剪）、Snip（滑动窗口丢旧过程）、
 * Reactive（摘要下沉）；preservedSegment 由 {@link PreservedSegment} 单独保障。</p>
 */
public interface CompactStrategy {

    /**
     * 执行压缩。
     *
     * @param messages 原始消息
     * @param context  上下文（threshold / budget 等）
     * @return 压缩后消息
     */
    List<ChatMessage> apply(List<ChatMessage> messages, Map<String, Object> context);
}
