package com.lucky.agent.cache.toolresult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 工具结果指纹生成器（R5 避陈旧命中）。
 *
 * <p>指纹 = 工具名 + 入参 + 文件 hash + 会话标识 + 记忆版本；文件变更 hash 变即失效重算。</p>
 */
public class Fingerprinter {

    /**
     * 生成工具调用指纹。
     *
     * @param toolName     工具名
     * @param args         入参
     * @param fileHash     关联文件 hash（无则空）
     * @param sessionId    会话标识
     * @param memoryVersion 记忆版本（无则 0）
     * @return 指纹串（SHA-256 截断）
     */
    public String fingerprint(String toolName, Map<String, Object> args,
                              String fileHash, String sessionId, long memoryVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName == null ? "" : toolName).append('#');
        sb.append(args == null ? "{}" : args.toString()).append('#');
        sb.append(fileHash == null ? "" : fileHash).append('#');
        sb.append(sessionId == null ? "" : sessionId).append('#');
        sb.append(memoryVersion);
        return sha256(sb.toString());
    }

    /** 计算内容 hash（用于文件指纹）。 */
    public String hashContent(String content) {
        if (content == null) {
            return "";
        }
        return sha256(content);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
