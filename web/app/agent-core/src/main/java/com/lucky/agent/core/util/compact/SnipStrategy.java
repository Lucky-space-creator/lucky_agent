package com.lucky.agent.core.util.compact;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snip：滑动窗口，丢弃最旧的"过程类"消息（不含 system / 最近 N 轮 / 关键结果）。
 * <p>与 {@link PreservedSegment} 配合，确保计划/安全约束/最近交互不下沉。</p>
 */
public class SnipStrategy implements CompactStrategy {

    private final int keepRecentTurns;

    public SnipStrategy(int keepRecentTurns) {
        this.keepRecentTurns = keepRecentTurns;
    }

    @Override
    public List<ChatMessage> apply(List<ChatMessage> messages, Map<String, Object> context) {
        if (messages == null || messages.size() <= keepRecentTurns) {
            return messages;
        }
        // 首条 system 保留
        ChatMessage head = messages.get(0);
        List<ChatMessage> tail = messages.subList(1, messages.size());
        // 保留最近 keepRecentTurns 条
        int from = Math.max(0, tail.size() - keepRecentTurns);
        List<ChatMessage> recent = new ArrayList<>(tail.subList(from, tail.size()));
        List<ChatMessage> result = new ArrayList<>();
        if (head instanceof SystemMessage) {
            result.add(head);
        }
        // 中间被 snip 的部分：保留全部工具结果（读/写结果是判定关键上下文，长度由
        // Microcompact 裁剪兜底；按长度丢弃会导致模型下一轮看不到结果而重新查询）
        List<ChatMessage> middle = tail.subList(0, from);
        for (ChatMessage m : middle) {
            if (m instanceof ToolExecutionResultMessage) {
                result.add(m);
            }
        }
        result.addAll(recent);
        return result;
    }
}
