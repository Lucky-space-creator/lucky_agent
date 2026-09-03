package com.lucky.agent.cache.toolresult;

import com.lucky.agent.cache.api.dto.CacheKey;
import com.lucky.agent.common.cache.CacheProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具结果指纹缓存（参数 + 文件hash + 会话标识 + 记忆版本 作指纹）。
 * <p>同指纹命中直接返回历史结果；指纹变更（文件改动）未命中重算。异常降级由调用方处理。</p>
 */
@Component
public class FingerprintCache {

    private final CacheProvider provider;
    private final Fingerprinter fingerprinter = new Fingerprinter();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public FingerprintCache(CacheProvider provider) {
        this.provider = provider;
    }

    /**
     * 尝试命中缓存。
     *
     * @return 命中返回结果字符串，未命中返回 null
     */
    public String getIfPresent(String toolName, Map<String, Object> args,
                               String fileHash, String sessionId, long memoryVersion) {
        String fp = fingerprinter.fingerprint(toolName, args, fileHash, sessionId, memoryVersion);
        CacheKey key = CacheKey.of("tool", "global", "fp", fp);
        String hit = provider.get(key.flat(), String.class);
        if (hit != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return hit;
    }

    /** 写入工具结果缓存。 */
    public void put(String toolName, Map<String, Object> args, String fileHash,
                    String sessionId, long memoryVersion, String result, long ttlSec) {
        String fp = fingerprinter.fingerprint(toolName, args, fileHash, sessionId, memoryVersion);
        CacheKey key = CacheKey.of("tool", "global", "fp", fp);
        provider.put(key.flat(), result, ttlSec);
    }

    /** 累计命中数（透明面板展示）。 */
    public long hits() {
        return hits.get();
    }

    /** 累计未命中数（透明面板展示）。 */
    public long misses() {
        return misses.get();
    }

    public Fingerprinter fingerprinter() {
        return fingerprinter;
    }
}
