package com.lucky.agent.core.runtime.gateway;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.dto.EngineRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型调用熔断网关：三态熔断器（CLOSED / OPEN / HALF_OPEN），防止模型端点持续故障时
 * 把整个编排拖进「每轮都等一次超时」的泥潭。
 *
 * <p>状态迁移：</p>
 * <pre>
 * CLOSED ──连续失败达阈值──▶ OPEN ──冷却到期──▶ HALF_OPEN ──探测成功──▶ CLOSED
 *                              ▲                    └──探测失败──┘
 * </pre>
 *
 * <p>并发要点：只在<b>状态迁移</b>时持锁，真实调用在锁外执行 —— 否则熔断器会把所有会话串行化，
 * 变成比故障本身更严重的性能瓶颈。HALF_OPEN 期间只放行<b>一个</b>探测请求，其余快速失败。</p>
 *
 * <p>编织位置：本类应处于<b>最外层</b>（{@code CircuitBreaker(Retry(Engine))}），
 * 这样 OPEN 状态下连带重试一起被短路，不会出现「熔断了还在退避重试」的浪费。</p>
 */
public class CircuitBreakerModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerModelGateway.class);

    /** 熔断器状态。 */
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final ModelGateway delegate;
    private final int failureThreshold;
    private final long openCooldownMs;

    private final Object lock = new Object();
    private boolean open;
    private boolean probeInFlight;
    private long openedAt;
    private int consecutiveFailures;

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong shortCircuits = new AtomicLong();
    private final AtomicLong trips = new AtomicLong();

    public CircuitBreakerModelGateway(ModelGateway delegate, int failureThreshold, long openCooldownMs) {
        this.delegate = delegate;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openCooldownMs = Math.max(1L, openCooldownMs);
    }

    @Override
    public EngineRunResult call(ConversationCtx ctx, Phase phase, String goal) {
        totalCalls.incrementAndGet();

        boolean probe = false;
        long elapsed = 0L;
        synchronized (lock) {
            if (open) {
                elapsed = System.currentTimeMillis() - openedAt;
                if (elapsed < openCooldownMs) {
                    shortCircuits.incrementAndGet();
                    log.warn("[circuit-breaker] OPEN 状态，直接拒绝模型调用（冷却剩余 {}ms）",
                            openCooldownMs - elapsed);
                    return EngineRunResult.error(ctx == null ? null : ctx.sessionId(), phase,
                            "模型端点已熔断（连续失败 " + consecutiveFailures + " 次），"
                                    + (openCooldownMs - elapsed) + "ms 后自动半开探测");
                }
                if (probeInFlight) {
                    shortCircuits.incrementAndGet();
                    return EngineRunResult.error(ctx == null ? null : ctx.sessionId(), phase,
                            "模型端点处于半开探测中，本次调用快速失败");
                }
                probeInFlight = true;
                probe = true;
            }
        }

        EngineRunResult result = null;
        RuntimeException thrown = null;
        try {
            result = delegate.call(ctx, phase, goal);
        } catch (RuntimeException e) {
            thrown = e;
        }

        synchronized (lock) {
            if (probe) {
                probeInFlight = false;
            }
            if (thrown != null || isFailure(result)) {
                consecutiveFailures++;
                if (probe || consecutiveFailures >= failureThreshold) {
                    open = true;
                    openedAt = System.currentTimeMillis();
                    trips.incrementAndGet();
                    log.warn("[circuit-breaker] 熔断器打开（连续失败 {} 次，{}ms 后半开探测）",
                            consecutiveFailures, openCooldownMs);
                }
            } else {
                if (open || probe) {
                    log.info("[circuit-breaker] 模型调用恢复，熔断器闭合");
                }
                open = false;
                openedAt = 0L;
                consecutiveFailures = 0;
            }
        }

        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    /** 当前状态（可观测/测试用）。 */
    public State state() {
        synchronized (lock) {
            if (!open) {
                return State.CLOSED;
            }
            return (System.currentTimeMillis() - openedAt >= openCooldownMs) ? State.HALF_OPEN : State.OPEN;
        }
    }

    /** 指标快照（供日志/traceId 回放）。 */
    public Map<String, Object> snapshot() {
        synchronized (lock) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("state", state().name());
            map.put("consecutiveFailures", consecutiveFailures);
            map.put("totalCalls", totalCalls.get());
            map.put("shortCircuits", shortCircuits.get());
            map.put("trips", trips.get());
            return map;
        }
    }

    /** 失败判定：无返回，或有错误且不属于 ASK/取消（业务语义不算故障）。 */
    private boolean isFailure(EngineRunResult result) {
        if (result == null) {
            return true;
        }
        String error = result.error();
        if (error == null || error.isBlank()) {
            return false;
        }
        String status = result.status() == null ? "" : result.status().toLowerCase();
        return !status.contains("ask") && !status.contains("cancel");
    }
}
