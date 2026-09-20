package com.lucky.agent.core.util.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行预算：每次循环边界检查 {@code maxTurns} / {@code maxBudget}（D19）。
 */
public class RunBudget {

    private final int maxTurns;
    private final long maxBudgetTokens;
    private final AtomicInteger turns = new AtomicInteger(0);
    private final AtomicLong usedTokens = new AtomicLong(0);

    public RunBudget(int maxTurns, long maxBudgetTokens) {
        this.maxTurns = maxTurns;
        this.maxBudgetTokens = maxBudgetTokens;
    }

    /** 本轮回合数自增并返回。 */
    public int incrementTurn() {
        return turns.incrementAndGet();
    }

    /** 是否已达回合上限。 */
    public boolean turnsExhausted() {
        return maxTurns > 0 && turns.get() >= maxTurns;
    }

    /** 累计 token 用量。 */
    public long addTokens(long tokens) {
        return usedTokens.addAndGet(tokens);
    }

    /** 是否已达 token 预算硬门槛。 */
    public boolean budgetExhausted() {
        return maxBudgetTokens > 0 && usedTokens.get() >= maxBudgetTokens;
    }

    /** 是否应终止循环（回合或预算耗尽）。 */
    public boolean exhausted() {
        return turnsExhausted() || budgetExhausted();
    }

    public long usedTokens() {
        return usedTokens.get();
    }

    public int maxTurns() {
        return maxTurns;
    }
}
