package com.lucky.agent.core.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 五级压缩流水线接口（D16）。
 *
 * <p>Phase 2 补齐：Microcompact / Snip / Reactive Compact 按场景降级，preservedSegment 保留最近 N 轮；
 * PreCompact/PostCompact 归档；压缩连续失败由 {@link TokenCircuitBreaker} 熔断，不无限重试。</p>
 */
public interface CompactionPipeline {

    /**
     * 压缩会话消息。
     *
     * @param messages 原始消息（LangChain4j ChatMessage 列表）
     * @param context  压缩上下文（threshold / budget 等）
     * @return 压缩后消息
     */
    List<ChatMessage> compact(List<ChatMessage> messages, Map<String, Object> context);
}
