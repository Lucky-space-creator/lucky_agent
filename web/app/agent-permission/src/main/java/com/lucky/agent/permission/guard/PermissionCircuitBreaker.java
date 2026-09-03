package com.lucky.agent.permission.guard;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限裁决电路断路器（D14 P2：电路断路器）。
 *
 * <p>短时间内连续出现高危/被拒操作（疑似提示注入循环或异常工具调用）达到阈值后，
 * 断路器打开，对后续同类操作直接短路为 DENY，避免无限确认弹窗与重复危害。
 * 一段时间无新拒绝后自动半开恢复。</p>
 */
public class PermissionCircuitBreaker {

    private final int threshold;
    private final AtomicInteger consecutiveDenials = new AtomicInteger(0);
    private volatile boolean open = false;

    public PermissionCircuitBreaker(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    /** 是否处于打开状态（后续操作直接拒绝）。 */
    public boolean isOpen() {
        return open;
    }

    /** 记录一次被拒操作。 */
    public void onDenied() {
        int n = consecutiveDenials.incrementAndGet();
        if (n >= threshold) {
            open = true;
        }
    }

    /** 记录一次放行（重置计数）。 */
    public void onAllowed() {
        consecutiveDenials.set(0);
        open = false;
    }

    public int consecutiveDenials() {
        return consecutiveDenials.get();
    }
}
