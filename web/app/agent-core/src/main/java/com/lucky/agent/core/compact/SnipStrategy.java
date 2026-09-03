package com.lucky.agent.core.compact;

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
        // 中间被 snip 的部分：仅保留被视为关键的 ToolExecutionResultMessage（保全关键结果）
        List<ChatMessage> middle = tail.subList(0, from);
        for (ChatMessage m : middle) {
            if (m instanceof ToolExecutionResultMessage tm && isKeyResult(tm)) {
                result.add(m);
            }
        }
        result.addAll(recent);
        return result;
    }

    private boolean isKeyResult(ToolExecutionResultMessage tm) {
        String text = tm.text();
        return text != null && (text.contains("KEY_RESULT") || text.length() < 200);
    }
}
