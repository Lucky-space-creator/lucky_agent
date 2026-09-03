package com.lucky.agent.memory.pipeline;

/**
 * 事实萃取：为记忆内容赋予置信度，冲突/去重聚合。
 */
public class FactExtractor {

    /**
     * 为内容赋置信度（启发式：更长更具体 → 更高置信）。
     *
     * @param content 内容
     * @return 置信度（0~1）
     */
    public double assignConfidence(String content) {
        if (content == null) {
            return 0.0;
        }
        int length = content.trim().length();
        if (length < 10) {
            return 0.4;
        }
        if (length > 80) {
            return 0.8;
        }
        return 0.6;
    }

    /**
     * 是否与已有记忆重复（简单文本重叠判断）。
     *
     * @param existing 已有内容
     * @param incoming 新内容
     * @return true 重复
     */
    public boolean isDuplicate(String existing, String incoming) {
        if (existing == null || incoming == null) {
            return false;
        }
        String e = existing.trim();
        String i = incoming.trim();
        if (e.equalsIgnoreCase(i)) {
            return true;
        }
        if (e.length() > 5 && e.contains(i)) {
            return true;
        }
        if (i.length() > 5 && i.contains(e)) {
            return true;
        }
        return false;
    }
}
