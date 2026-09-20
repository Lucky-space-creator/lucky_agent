package com.lucky.agent.memory.support.pipeline;

/**
 * 噪声过滤层：丢弃低质输入（空/过短/纯报错栈），减少记忆污染。
 */
public class NoiseFilter {

    /**
     * 是否为噪声内容（应被过滤，不进入记忆）。
     *
     * @param content 待判定内容
     * @return true 为噪声
     */
    public boolean isNoise(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String trimmed = content.trim();
        if (trimmed.length() < 4) {
            return true;
        }
        // 纯报错栈（以 Exception 开头且含堆栈行）不沉淀为记忆
        if (trimmed.startsWith("Exception") && trimmed.contains("\tat ")) {
            return true;
        }
        // 纯占位/噪声词
        String lower = trimmed.toLowerCase();
        return lower.equals("ok") || lower.equals("好的") || lower.equals("嗯")
                || lower.matches("[-_=*]{3,}");
    }
}
