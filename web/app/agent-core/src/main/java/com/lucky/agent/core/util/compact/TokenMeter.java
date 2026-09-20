package com.lucky.agent.core.util.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Token 计量器（§6 / §4.13）。
 * <p>轻量 token 估算：以字符数按经验系数折算（中文约 1 字≈1 token，英文约 4 字符≈1 token），
 * 与 Phase1 的 char/4 估算保持兼容，但不依赖外部 tokenizer 实现，避免引入额外重型依赖。
 * 提供上下文占用率计算（已用 ÷ 模型窗口）。</p>
 */
public class TokenMeter {

    /** 估算单条消息 token 数（混合中英文启发式）。 */
    public int count(ChatMessage message) {
        String text = textOf(message);
        return text == null ? 0 : estimateText(text);
    }

    /** 估算消息序列总 token 数。 */
    public int count(List<ChatMessage> messages) {
        int total = 0;
        if (messages == null) {
            return 0;
        }
        for (ChatMessage m : messages) {
            total += count(m);
        }
        return total;
    }

    /**
     * 混合中英文 token 估算。
     * <ul>
     *   <li>中文/全角字符：每张 ≈ 1 token</li>
     *   <li>英文/半角连续词：每 ~4 字符 ≈ 1 token</li>
     *   <li>标点与空白：并入相邻类别，粗略近似</li>
     * </ul>
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80) { // 中日韩及全角字符范围（粗略）
                cjk++;
            } else if (!Character.isWhitespace(c) && c != '\n' && c != '\r' && c != '\t') {
                latin++;
            }
        }
        return cjk + (latin / 4) + 1;
    }

    /**
     * 上下文占用率（已用 ÷ 窗口）。
     *
     * @param usedTokens 已用 token
     * @param window     模型上下文窗口（<=0 视为未知窗口，返回 0）
     * @return 占用率 [0,1+]
     */
    public double occupancy(int usedTokens, int window) {
        if (window <= 0) {
            return 0d;
        }
        return (double) usedTokens / window;
    }

    private String textOf(ChatMessage msg) {
        if (msg == null) {
            return null;
        }
        if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
            return um.singleText();
        }
        if (msg instanceof dev.langchain4j.data.message.AiMessage am) {
            return am.text();
        }
        if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
            return sm.text();
        }
        if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
