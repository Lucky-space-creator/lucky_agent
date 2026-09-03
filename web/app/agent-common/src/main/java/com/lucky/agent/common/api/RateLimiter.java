package com.lucky.agent.common.api;

/**
 * 限流抽象（§2.2），预留 Sentinel 替换点。
 *
 * <p>令牌桶式限流契约，用于单实例入口与模型端点限流；本地实现基于 Guava RateLimiter。</p>
 */
public interface RateLimiter {

    /**
     * 尝试获取 permits 个许可。
     *
     * @param permits 需要的许可数
     * @return true 获取成功
     */
    boolean tryAcquire(int permits);

    /** 每秒放行速率。 */
    double rate();
}
