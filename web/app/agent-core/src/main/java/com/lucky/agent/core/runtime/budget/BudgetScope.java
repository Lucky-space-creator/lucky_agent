package com.lucky.agent.core.runtime.budget;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单级预算作用域（线程安全）：token / 时间 / 重试 三类限制 + 用量计数。
 * <p>无限制约定：限制值 &lt;= 0 表示「不限制」。</p>
 */
public class BudgetScope {

    private final BudgetLevel level;
    private final long maxTokens;
    private final long maxTimeMs;
    private final int maxRetries;

    private final AtomicLong usedTokens = new AtomicLong();
    private final AtomicInteger usedRetries = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();

    public BudgetScope(BudgetLevel level, long maxTokens, long maxTimeMs, int maxRetries) {
        this.level = level;
        this.maxTokens = maxTokens;
        this.maxTimeMs = maxTimeMs;
        this.maxRetries = maxRetries;
    }

    public BudgetLevel level() {
        return level;
    }

    public long usedTokens() {
        return usedTokens.get();
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public void addTokens(long tokens) {
        if (tokens > 0) {
            usedTokens.addAndGet(tokens);
        }
    }

    public boolean tokensExhausted() {
        return maxTokens > 0 && usedTokens.get() >= maxTokens;
    }

    public boolean timeExhausted() {
        return maxTimeMs > 0 && elapsedMs() >= maxTimeMs;
    }

    public boolean retriesExhausted() {
        return maxRetries > 0 && usedRetries.get() >= maxRetries;
    }

    public boolean exhausted() {
        return tokensExhausted() || timeExhausted() || retriesExhausted();
    }

    /** 消耗一次重试额度；返回是否仍允许重试。 */
    public boolean tryConsumeRetry() {
        if (maxRetries <= 0) {
            return true;
        }
        return usedRetries.incrementAndGet() <= maxRetries;
    }

    /** 耗尽原因（未耗尽返回 null）。 */
    public String exhaustedReason() {
        if (tokensExhausted()) {
            return level + ":max_tokens(" + usedTokens.get() + "/" + maxTokens + ")";
        }
        if (timeExhausted()) {
            return level + ":max_time(" + elapsedMs() + "ms/" + maxTimeMs + "ms)";
        }
        if (retriesExhausted()) {
            return level + ":max_retries(" + usedRetries.get() + "/" + maxRetries + ")";
        }
        return null;
    }
}
