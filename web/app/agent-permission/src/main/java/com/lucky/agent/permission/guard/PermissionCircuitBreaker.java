package com.lucky.agent.permission.guard;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限裁决电路断路器（D14 P2：电路断路器）。
 *
 * <p>短时间内连续出现高危/被拒操作（疑似提示注入循环或异常工具调用）达到阈值后，
 * 断路器打开，对后续同类操作直接短路为 DENY，避免无限确认弹窗与重复危害。</p>
 *
 * <p><b>自愈（半开恢复）</b>：打开后进入冷却窗口（{@link #openMillis}），窗口内全部短路为 DENY；
 * 冷却结束后自动转为「半开」并放行一次试探：若试探被放行则完全闭合、恢复正常；
 * 若试探仍被拒则重新打开并刷新冷却计时。这样可避免断路器因一次性异常而<b>永久锁死</b>
 * （此前实现缺少恢复逻辑，打开后永远 DENY）。</p>
 */
public class PermissionCircuitBreaker {

    /** 半开恢复前的冷却窗口（毫秒）。 */
    private static final long DEFAULT_OPEN_MILLIS = 30_000L;

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long openMillis;
    private final AtomicInteger consecutiveDenials = new AtomicInteger(0);
    private volatile State state = State.CLOSED;
    private volatile long openSince = 0L;

    public PermissionCircuitBreaker(int threshold) {
        this(threshold, DEFAULT_OPEN_MILLIS);
    }

    public PermissionCircuitBreaker(int threshold, long openMillis) {
        this.threshold = Math.max(1, threshold);
        this.openMillis = Math.max(1_000L, openMillis);
    }

    /**
     * 是否处于打开状态（后续操作直接拒绝）。
     * 若已打开且冷却窗口已过，则转为「半开」并放行一次试探（返回 false）。
     */
    public boolean isOpen() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openSince >= openMillis) {
                state = State.HALF_OPEN;
            } else {
                return true;
            }
        }
        return false;
    }

    /** 记录一次被拒操作（达到阈值则打开，并刷新冷却计时）。 */
    public void onDenied() {
        int n = consecutiveDenials.incrementAndGet();
        if (n >= threshold) {
            state = State.OPEN;
            openSince = System.currentTimeMillis();
        }
    }

    /** 记录一次放行（重置计数并完全闭合）。 */
    public void onAllowed() {
        consecutiveDenials.set(0);
        state = State.CLOSED;
        openSince = 0L;
    }

    public int consecutiveDenials() {
        return consecutiveDenials.get();
    }

    public String stateName() {
        return state.name();
    }
}
