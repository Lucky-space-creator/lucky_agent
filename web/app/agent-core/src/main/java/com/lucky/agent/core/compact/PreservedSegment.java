package com.lucky.agent.core.compact;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 保全段（D16）：压缩时强制保留的部分，绝不下沉。
 * <ul>
 *   <li>System 提示（含权限约束/计划指令）</li>
 *   <li>最近 N 轮交互（用户/AI/工具结果）</li>
 *   <li>标记为关键结果的工具结果（含 KEY_RESULT）</li>
 * </ul>
 */
public class PreservedSegment {

    private final int keepRecentTurns;

    public PreservedSegment(int keepRecentTurns) {
        this.keepRecentTurns = keepRecentTurns;
    }

    /** 判断是否应被保全（不下沉）。 */
    public boolean shouldPreserve(ChatMessage message, boolean isRecent) {
        if (message instanceof SystemMessage) {
            return true;
        }
        if (isRecent) {
            return true;
        }
        if (message instanceof ToolExecutionResultMessage tm) {
            String text = tm.text();
            return text != null && text.contains("KEY_RESULT");
        }
        return false;
    }

    /** 计算最近 N 轮的起始下标（从尾部倒推）。 */
    public int recentFromIndex(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return Math.max(0, messages.size() - keepRecentTurns);
    }

    /** 提取保全段（用于测试/审计）。 */
    public List<ChatMessage> extract(List<ChatMessage> messages) {
        int from = recentFromIndex(messages);
        List<ChatMessage> preserved = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (shouldPreserve(messages.get(i), i >= from)) {
                preserved.add(messages.get(i));
            }
        }
        return preserved;
    }
}
