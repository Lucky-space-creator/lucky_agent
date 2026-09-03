package com.lucky.agent.common.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本机内存缓存实现（按命名空间隔离）。
 *
 * <p>简单 TTL 缓存，供 MVP 使用；演进时替换为 Caffeine 或远端缓存实现。</p>
 */
public class InMemoryCacheProvider implements CacheProvider {

    private static final class Entry {
        final Object value;
        final long expireAtMs;

        Entry(Object value, long expireAtMs) {
            this.value = value;
            this.expireAtMs = expireAtMs;
        }
    }

    private final String namespace;
    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    public InMemoryCacheProvider(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMs > 0 && System.currentTimeMillis() > entry.expireAtMs) {
            store.remove(key);
            return null;
        }
        Object value = entry.value;
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        if (value instanceof String s && type == String.class) {
            return (T) s;
        }
        return null;
    }

    @Override
    public void put(String key, Object value, long ttlSec) {
        long expireAt = ttlSec > 0 ? System.currentTimeMillis() + ttlSec * 1000 : 0;
        store.put(key, new Entry(value, expireAt));
    }

    @Override
    public void invalidate(String key) {
        store.remove(key);
    }

    @Override
    public void clear() {
        store.clear();
    }

    /** 主动回收已过期的条目（供 TtlSweeper 周期调用，不误删未过期项）。 */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expireAtMs > 0 && now > e.getValue().expireAtMs);
    }

    @Override
    public int size() {
        return store.size();
    }
}
