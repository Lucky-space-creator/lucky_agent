package com.lucky.agent.common.concurrent;

import com.lucky.agent.common.api.ConcurrencyGuard;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 信号量并发守卫：模型在途数控制（R6），防线程耗尽。
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * if (guard.tryAcquire(1000)) {
 *     try { ... } finally { guard.release(); }
 * }
 * }</pre>
 */
public class SemaphoreGuard implements ConcurrencyGuard {

    private final String name;
    private final Semaphore semaphore;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    public SemaphoreGuard(String name, int permits) {
        this.name = name;
        this.semaphore = new Semaphore(permits);
    }

    @Override
    public String name() {
        return name;
    }

    /** 当前可用的许可数（用于监控）。 */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /** 当前在途请求数（用于流量监控）。 */
    public int inFlight() {
        return inFlightCount.get();
    }

    @Override
    public boolean tryAcquire(long timeoutMs) {
        try {
            boolean acquired;
            if (timeoutMs <= 0) {
                acquired = semaphore.tryAcquire();
            } else {
                acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            }
            if (acquired) {
                inFlightCount.incrementAndGet();
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void release() {
        inFlightCount.decrementAndGet();
        semaphore.release();
    }
}
