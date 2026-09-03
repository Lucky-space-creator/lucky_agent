package com.lucky.agent.cache.api.dto;

/**
 * 缓存键（按 userId + workspaceId 隔离）。
 */
public record CacheKey(String userId, String workspaceId, String scope, String key) {

    /** 拼装为命名空间隔离的扁平键。 */
    public String flat() {
        return userId + "|" + workspaceId + "|" + scope + "|" + key;
    }

    public static CacheKey of(String userId, String workspaceId, String scope, String key) {
        return new CacheKey(userId, workspaceId, scope, key);
    }
}
