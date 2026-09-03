package com.lucky.agent.common.cache;

/**
 * 缓存抽象：业务只关心 get/put，落盘/内存策略由实现决定。
 *
 * <p>演进预案：当前本机内存实现；未来可切换远端缓存（带版本指纹）而不动业务代码。</p>
 */
public interface CacheProvider {

    /** 缓存键前缀命名空间。 */
    String namespace();

    /**
     * 读取缓存。
     *
     * @param key 缓存键
     * @param <T> 类型
     * @return 命中的值，未命中或过期返回 null
     */
    <T> T get(String key, Class<T> type);

    /**
     * 写入缓存。
     *
     * @param key     缓存键
     * @param value   值
     * @param ttlSec  过期秒数，<=0 表示不过期
     */
    void put(String key, Object value, long ttlSec);

    /** 删除缓存。 */
    void invalidate(String key);

    /** 清空。 */
    void clear();

    /** 主动回收过期条目（默认实现等同 clear，演进实现可只清过期项）。 */
    default void evictExpired() {
        clear();
    }

    /** 当前缓存条目数（供状态面板展示；默认 0）。 */
    default int size() {
        return 0;
    }
}
