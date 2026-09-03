package com.lucky.agent.core.compact;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Microcompact：裁剪过大的工具结果文本（MVP 已有能力，移入策略）。
 */
public class MicrocompactStrategy implements CompactStrategy {

    private final int maxToolResultLength;

    public MicrocompactStrategy(int maxToolResultLength) {
        this.maxToolResultLength = maxToolResultLength;
    }

    @Override
    public List<ChatMessage> apply(List<ChatMessage> messages, Map<String, Object> context) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage tm) {
                String text = tm.text();
                if (text != null && text.length() > maxToolResultLength) {
                    String trimmed = text.substring(0, maxToolResultLength) + "…（工具结果已裁剪）";
                    result.add(ToolExecutionResultMessage.from(tm.id(), tm.toolName(), trimmed));
                    continue;
                }
            }
            result.add(msg);
        }
        return result;
    }
}
