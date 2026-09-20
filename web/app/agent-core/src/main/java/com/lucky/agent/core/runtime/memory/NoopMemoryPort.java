package com.lucky.agent.core.runtime.memory;

import java.util.Map;

/**
 * 空实现：不接入记忆时使用（保证薄主循环可独立运行）。
 * <p>接入真实记忆（agent-memory / 分层 Hint 记忆 / Dream 合成）时以自定义实现覆盖。</p>
 */
public class NoopMemoryPort implements MemoryPort {

    @Override
    public String recall(String sessionId, String query) {
        return "";
    }

    @Override
    public void recordLongTerm(String sessionId, String content, Map<String, Object> structured) {
        // no-op
    }

    @Override
    public void compact(String sessionId, Map<String, Object> structuredFields) {
        // no-op
    }
}
