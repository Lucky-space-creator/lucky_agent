package com.lucky.agent.core.compact;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reactive Compact：摘要下沉。把"过程类"中间消息（旧的工具结果/中间 AI 思考）摘要为单行，
 * 避免一次性截断；与 {@link PreservedSegment} 配合，保全计划/约束/最近交互。
 */
public class ReactiveCompactStrategy implements CompactStrategy {

    private final int summaryLength;

    public ReactiveCompactStrategy(int summaryLength) {
        this.summaryLength = summaryLength;
    }

    @Override
    public List<ChatMessage> apply(List<ChatMessage> messages, Map<String, Object> context) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        StringBuilder middleSummary = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m instanceof SystemMessage) {
                result.add(m); // 系统提示永远保全
                continue;
            }
            // 末段（最近交互）保全
            if (i >= messages.size() - 1) {
                result.add(m);
                continue;
            }
            if (m instanceof ToolExecutionResultMessage || m instanceof AiMessage) {
                String text = textOf(m);
                if (text != null && !text.isBlank()) {
                    middleSummary.append(text.length() > 80 ? text.substring(0, 80) : text).append(" | ");
                }
                continue;
            }
            result.add(m); // 用户消息保留
        }
        if (middleSummary.length() > 0) {
            String summary = "[压缩摘要] " + middleSummary + "（过程已摘要）";
            if (summary.length() > summaryLength) {
                summary = summary.substring(0, summaryLength);
            }
            result.add(1, AiMessage.from(summary));
        }
        return result;
    }

    private String textOf(ChatMessage msg) {
        if (msg instanceof AiMessage am) {
            return am.text();
        }
        if (msg instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
