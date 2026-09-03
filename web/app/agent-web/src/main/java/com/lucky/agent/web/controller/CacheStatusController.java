package com.lucky.agent.web.controller;

import com.lucky.agent.cache.config.CacheProperties;
import com.lucky.agent.cache.session.SessionMessageCache;
import com.lucky.agent.cache.toolresult.FingerprintCache;
import com.lucky.agent.common.cache.CacheProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 缓存模块状态接口（透明面板：命中率 + 条目数 + TTL 配置）。
 */
@RestController
@RequestMapping("/api/cache")
public class CacheStatusController {

    private final FingerprintCache fingerprintCache;
    private final SessionMessageCache sessionMessageCache;
    private final CacheProperties properties;
    private final CacheProvider provider;

    public CacheStatusController(FingerprintCache fingerprintCache,
                                 SessionMessageCache sessionMessageCache,
                                 CacheProperties properties,
                                 CacheProvider provider) {
        this.fingerprintCache = fingerprintCache;
        this.sessionMessageCache = sessionMessageCache;
        this.properties = properties;
        this.provider = provider;
    }

    /** 缓存运行状态快照。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        long hits = fingerprintCache.hits();
        long misses = fingerprintCache.misses();
        double hitRate = (hits + misses) == 0 ? 0D : (double) hits / (hits + misses);
        return Map.of(
                "fingerprintCacheHits", hits,
                "fingerprintCacheMisses", misses,
                "hitRate", hitRate,
                "cacheEntries", provider.size(),
                "sessionTtlDays", properties.sessionTtlDays(),
                "toolTtlDays", properties.toolTtlDays(),
                "sweepHours", properties.sweepHours());
    }
}
