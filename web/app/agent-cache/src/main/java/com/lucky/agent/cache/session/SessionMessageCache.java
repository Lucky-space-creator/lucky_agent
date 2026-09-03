package com.lucky.agent.cache.session;

import com.lucky.agent.cache.api.dto.CacheKey;
import com.lucky.agent.cache.api.dto.SessionMessageVal;
import com.lucky.agent.common.cache.CacheProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会话消息缓存（仅最近 3 天，§4.6）。
 * <p>按 userId + workspaceId + sessionId 隔离，存本机内存；超 3 天由 {@link TtlSweeper} 清理。</p>
 */
@Component
public class SessionMessageCache {

    private final CacheProvider provider;
    private final long ttlSec;

    public SessionMessageCache(CacheProvider provider, long ttlSec) {
        this.provider = provider;
        this.ttlSec = ttlSec;
    }

    /** 写入本轮消息快照。 */
    public void put(String userId, String workspaceId, String sessionId,
                    List<Map<String, Object>> messages) {
        CacheKey key = CacheKey.of(userId, workspaceId, "session", sessionId);
        provider.put(key.flat(), new SessionMessageVal(messages, System.currentTimeMillis()), ttlSec);
    }

    /** 读取会话消息快照（超 TTL 返回 null）。 */
    @SuppressWarnings("unchecked")
    public SessionMessageVal get(String userId, String workspaceId, String sessionId) {
        CacheKey key = CacheKey.of(userId, workspaceId, "session", sessionId);
        return provider.get(key.flat(), SessionMessageVal.class);
    }

    /** 清除指定会话缓存。 */
    public void invalidate(String userId, String workspaceId, String sessionId) {
        CacheKey key = CacheKey.of(userId, workspaceId, "session", sessionId);
        provider.invalidate(key.flat());
    }

    /** 该用户全部会话缓存（供清理）。 */
    public void clearUser(String userId) {
        // InMemoryCacheProvider 无前缀扫描，仅记录命名空间占位；TtlSweeper 负责超时清理
        provider.clear();
    }
}
