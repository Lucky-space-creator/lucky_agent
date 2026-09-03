package com.lucky.agent.cache.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 会话消息缓存值（仅最近 3 天）。
 */
public record SessionMessageVal(List<Map<String, Object>> messages, long createdAtMs) {
}
