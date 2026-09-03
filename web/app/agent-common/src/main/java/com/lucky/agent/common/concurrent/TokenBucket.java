package com.lucky.agent.common.concurrent;

import com.lucky.agent.common.api.RateLimiter;

/**
 * 令牌桶限流实现（Guava），预留 Sentinel 替换点。
 */
public class TokenBucket implements RateLimiter {

    private final String name;
    private final com.google.common.util.concurrent.RateLimiter rateLimiter;

    public TokenBucket(String name, double permitsPerSecond) {
        this.name = name;
        this.rateLimiter = com.google.common.util.concurrent.RateLimiter.create(permitsPerSecond);
    }

    public String name() {
        return name;
    }

    @Override
    public boolean tryAcquire(int permits) {
        return rateLimiter.tryAcquire(permits);
    }

    @Override
    public double rate() {
        return rateLimiter.getRate();
    }
}
