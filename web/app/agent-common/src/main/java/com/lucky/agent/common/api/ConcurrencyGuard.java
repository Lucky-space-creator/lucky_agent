package com.lucky.agent.common.api;

/**
 * 并发守卫抽象（§2.2），本地实现与未来远端实现可互换（演进预案）。
 *
 * <p>当前用 JDK 信号量/读写锁本地实现；未来换 Redis/Sentinel 只需替换 agent-common 实现，
 * 业务代码不动。</p>
 */
public interface ConcurrencyGuard {

    /** 守卫名（用于日志/监控）。 */
    String name();

    /**
     * 尝试获取许可。
     *
     * @param timeoutMs 最大等待毫秒数，<=0 表示不等待
     * @return true 获取成功，false 超时未获取
     */
    boolean tryAcquire(long timeoutMs);

    /** 释放许可，必须在 try-finally 或 doFinally 中调用。 */
    void release();
}
