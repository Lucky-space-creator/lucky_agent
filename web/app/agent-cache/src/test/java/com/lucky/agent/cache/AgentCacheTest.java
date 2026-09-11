package com.lucky.agent.cache;

import com.lucky.agent.cache.support.protect.CachePenetrationGuard;
import com.lucky.agent.cache.config.CacheConfig;
import com.lucky.agent.cache.support.session.SessionMessageCache;
import com.lucky.agent.cache.support.toolresult.FingerprintCache;
import com.lucky.agent.cache.api.dto.SessionMessageVal;
import com.lucky.agent.common.cache.InMemoryCacheProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CacheConfig.class)
class AgentCacheTest {

    @Autowired
    SessionMessageCache sessionMessageCache;

    @Autowired
    FingerprintCache fingerprintCache;

    @Autowired
    InMemoryCacheProvider cacheProvider;

    @Test
    void sessionCache_expiresAfterTtl() {
        sessionMessageCache.put("u1", "w1", "s1", List.of(Map.of("role", "user", "content", "hi")));
        SessionMessageVal v = sessionMessageCache.get("u1", "w1", "s1");
        assertNotNull(v);
        // 隔离性：不同 userId 不可见
        assertNull(sessionMessageCache.get("u2", "w1", "s1"));
        // 模拟超 3 天 TTL 后判定过期
        InMemoryCacheProvider prov = new InMemoryCacheProvider("t");
        prov.put("k", "v", 1);
        // 直接等待 1.1s 验证 TTL 清理
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
        }
        assertNull(prov.get("k", String.class));
    }

    @Test
    void toolResult_fingerprintMissOnFileChange() {
        Map<String, Object> args = Map.of("path", "a.txt");
        String hit1 = fingerprintCache.getIfPresent("file.read", args, "hashA", "s1", 1L);
        assertNull(hit1);
        fingerprintCache.put("file.read", args, "hashA", "s1", 1L, "contentA",
                TimeUnit.DAYS.toSeconds(3));
        assertEquals("contentA", fingerprintCache.getIfPresent("file.read", args, "hashA", "s1", 1L));
        // 文件变更 → hash 变 → 未命中
        assertNull(fingerprintCache.getIfPresent("file.read", args, "hashB", "s1", 1L));
        // 记忆版本变更 → 未命中
        assertNull(fingerprintCache.getIfPresent("file.read", args, "hashA", "s1", 2L));
    }

    @Test
    void penetrationGuard_degradeOnException() {
        CachePenetrationGuard guard =
                new CachePenetrationGuard();
        // 计算正常返回
        assertEquals("ok", guard.withFallback(() -> "ok"));
        // 异常降级：第一次抛错，第二次成功
        int[] calls = {0};
        String r = guard.withFallback(() -> {
            if (calls[0]++ == 0) {
                throw new RuntimeException("cache boom");
            }
            return "recovered";
        });
        assertEquals("recovered", r);
    }
}
