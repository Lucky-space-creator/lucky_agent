package com.lucky.agent.core.runtime.gateway;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.dto.EngineRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型调用重试网关：对<b>可重试的瞬时故障</b>做指数退避 + 抖动重试。
 *
 * <p>两条关键约定，避免「重试把事情弄得更糟」：</p>
 * <ol>
 *   <li><b>只重试瞬时故障</b>：限流（429 / rate limit）、网关故障（5xx）、连接类错误（reset / refused /
 *       unavailable）、超时。参数错误、鉴权失败、内容被拒等<b>确定性失败绝不重试</b>（重试必然再失败，只浪费配额）。</li>
 *   <li><b>ASK 与取消不重试</b>：{@code ask} 是业务语义（等待用户确认），取消是用户意图，都不属于故障。</li>
 * </ol>
 *
 * <p>退避序列 {@code base, 2·base, 4·base …}（上限 {@value #MAX_BACKOFF_FACTOR} 倍），
 * 并叠加 ±{@value #JITTER_RATIO} 的抖动，避免多会话同时重试造成<b>重试风暴</b>（惊群）。</p>
 */
public class RetryModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(RetryModelGateway.class);

    /** 退避倍数上限（防止退避无限增长阻塞编排）。 */
    private static final int MAX_BACKOFF_FACTOR = 8;

    /** 抖动比例（±25%）。 */
    private static final double JITTER_RATIO = 0.25;

    /** 瞬时故障特征词（小写匹配）。 */
    private static final List<String> TRANSIENT_MARKERS = List.of(
            "429", "500", "502", "503", "504",
            "rate limit", "too many requests", "overloaded", "throttl",
            "timeout", "timed out", "超时",
            "connection", "connect", "reset by peer", "refused", "unreachable",
            "unavailable", "服务不可用", "繁忙", "暂时");

    /** 确定性失败特征词：命中即放弃重试。 */
    private static final List<String> PERMANENT_MARKERS = List.of(
            "401", "403", "404", "400", "invalid", "unauthorized", "forbidden",
            "api key", "apikey", "鉴权", "密钥", "参数错误");

    private final ModelGateway delegate;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final double jitterRatio;
    private final AtomicLong retryCount = new AtomicLong();

    public RetryModelGateway(ModelGateway delegate, int maxAttempts, long baseBackoffMs) {
        this(delegate, maxAttempts, baseBackoffMs, JITTER_RATIO);
    }

    public RetryModelGateway(ModelGateway delegate, int maxAttempts, long baseBackoffMs, double jitterRatio) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(0L, baseBackoffMs);
        this.jitterRatio = Math.max(0.0, jitterRatio);
    }

    /** 累计重试次数（可观测）。 */
    public long retryCount() {
        return retryCount.get();
    }

    @Override
    public EngineRunResult call(ConversationCtx ctx, Phase phase, String goal) {
        EngineRunResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = delegate.call(ctx, phase, goal);
            if (!isTransientFailure(result)) {
                return result; // 成功，或确定性失败 → 直接返回
            }
            if (attempt == maxAttempts) {
                break;
            }
            long backoff = backoffFor(attempt);
            retryCount.incrementAndGet();
            log.warn("[model-retry] 第 {}/{} 次调用遇瞬时故障（{}），{}ms 后重试",
                    attempt, maxAttempts, brief(result), backoff);
            if (!sleep(backoff)) {
                return result; // 被中断：立即返回已有结果，不吞掉中断标志
            }
        }
        log.warn("[model-retry] 已重试 {} 次仍失败：{}", maxAttempts - 1, brief(result));
        return result;
    }

    /** 是否属于「值得重试」的瞬时故障。 */
    boolean isTransientFailure(EngineRunResult result) {
        if (result == null) {
            return true; // 无返回：多为上游异常被吞，值得重试
        }
        String error = result.error();
        if (error == null || error.isBlank()) {
            return false; // 成功
        }
        String status = result.status() == null ? "" : result.status().toLowerCase();
        if (status.contains("ask") || status.contains("cancel")) {
            return false;
        }
        String text = error.toLowerCase();
        if (PERMANENT_MARKERS.stream().anyMatch(text::contains)) {
            return false;
        }
        return TRANSIENT_MARKERS.stream().anyMatch(text::contains);
    }

    /** 指数退避 + 抖动。 */
    long backoffFor(int attempt) {
        long factor = Math.min(1L << Math.min(attempt - 1, 3), MAX_BACKOFF_FACTOR);
        long base = baseBackoffMs * factor;
        if (base <= 0 || jitterRatio <= 0) {
            return base;
        }
        long jitter = (long) (base * jitterRatio);
        return base - jitter + ThreadLocalRandom.current().nextLong(jitter * 2 + 1);
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String brief(EngineRunResult result) {
        if (result == null) {
            return "无返回";
        }
        String message = result.error() == null ? result.status() : result.error();
        if (message == null) {
            return "unknown";
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
