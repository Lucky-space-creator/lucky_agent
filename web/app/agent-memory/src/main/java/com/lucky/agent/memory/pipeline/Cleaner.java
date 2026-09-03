package com.lucky.agent.memory.pipeline;

/**
 * 数据清洗层：结构化抽取/脱敏（基础实现）。
 *
 * <p>折叠空白、脱敏明显敏感串（如 apiKey/sk- 开头、Bearer token）。</p>
 */
public class Cleaner {

    private static final String[] SENSITIVE_PREFIXES = {"sk-", "Bearer ", "api_key=", "apikey="};

    /**
     * 清洗记忆内容。
     *
     * @param content 原始内容
     * @return 清洗后内容
     */
    public String clean(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.trim().replaceAll("\\s+", " ");
        for (String prefix : SENSITIVE_PREFIXES) {
            int idx = cleaned.toLowerCase().indexOf(prefix.toLowerCase());
            if (idx >= 0) {
                int end = idx + prefix.length() + 24;
                end = Math.min(end, cleaned.length());
                cleaned = cleaned.substring(0, idx) + prefix + "***";
                break;
            }
        }
        return cleaned;
    }
}
