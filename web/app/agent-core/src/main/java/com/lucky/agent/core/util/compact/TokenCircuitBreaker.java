package com.lucky.agent.core.util.compact;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压缩熔断（§4.13 / D16）。
 * <p>连续失败达到阈值后停止重试压缩，转为兜底（不无限计费/不无限压缩）。
 * 成功后自动重置计数。</p>
 */
public class TokenCircuitBreaker {

    private final int failureThreshold;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /**
     * -- GETTER --
     * 是否处于熔断打开状态（停止压缩重试）。
     */
    @Getter
    private volatile boolean open = false;

    public TokenCircuitBreaker(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    /** 记录一次压缩失败。 */
    public void onFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= failureThreshold) {
            open = true;
        }
    }

    /** 记录一次压缩成功（重置）。 */
    public void onSuccess() {
        consecutiveFailures.set(0);
        open = false;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
