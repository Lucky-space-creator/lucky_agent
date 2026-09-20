package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionMetrics;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.ExecutionStatus;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 重试中间件：以指数退避包裹真实工具调用。
 *
 * <p>用环绕钩子实现是必要的——before/after 通知型钩子无法触发「再执行一次」。</p>
 *
 * <p>约束：</p>
 * <ul>
 *   <li>仅重试 {@code FAILED / TIMEOUT}；{@code ASK / CANCELLED / BUDGET_EXHAUSTED} 直接透传（重试无意义且有害）；</li>
 *   <li>工具可通过 {@link RuntimeTool#retryable()} 声明不可重试（非幂等副作用），
 *       未注册工具的调用不重试；</li>
 *   <li>重试次数记入 {@link ExecutionMetrics#retries()}，供预算与可观测性统计。</li>
 * </ul>
 */
public class RetryMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(RetryMiddleware.class);

    private final ToolRegistry tools;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public RetryMiddleware(ToolRegistry tools, int maxAttempts, long baseBackoffMs) {
        this.tools = tools;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(0L, baseBackoffMs);
    }

    @Override
    public int order() {
        return MiddlewareOrder.RETRY;
    }

    @Override
    public ExecutionResult aroundTool(MiddlewareContext ctx, Supplier<ExecutionResult> next) {
        if (maxAttempts <= 1 || !isRetryable(ctx)) {
            return next.get();
        }

        ExecutionResult result = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            result = next.get();
            if (result.isSuccess() || !isRetryableStatus(result.status())) {
                break;
            }
            if (attempt < maxAttempts) {
                long backoff = backoffFor(attempt);
                log.info("[retry] 工具 {} 第 {}/{} 次失败（{}），{}ms 后重试",
                        ctx.toolCall().name(), attempt, maxAttempts, result.status(), backoff);
                if (!sleep(backoff)) {
                    break; // 被中断：停止重试，返回已有结果
                }
                ctx.attribute("retryAttempt", attempt);
            }
        }

        if (result == null) {
            return ExecutionResult.failure("工具未执行");
        }
        int retries = attempt - 1;
        if (retries <= 0) {
            return result;
        }
        ExecutionMetrics metrics = result.metrics();
        ExecutionMetrics merged = new ExecutionMetrics(metrics.tokenUsed(), metrics.durationMs(),
                metrics.retries() + retries, metrics.toolCalls());
        return new ExecutionResult(result.status(), result.output(), result.artifacts(), result.error(),
                merged, result.trace(), result.metadata());
    }

    private boolean isRetryable(MiddlewareContext ctx) {
        String name = ctx.toolCall() == null ? null : ctx.toolCall().name();
        if (name == null) {
            return false;
        }
        return tools.find(name).map(RuntimeTool::retryable).orElse(false);
    }

    private boolean isRetryableStatus(ExecutionStatus status) {
        return status == ExecutionStatus.FAILED || status == ExecutionStatus.TIMEOUT;
    }

    /** 指数退避：base, 2·base, 4·base…（上限 8 倍，避免长尾阻塞）。 */
    private long backoffFor(int attempt) {
        long factor = Math.min(1L << Math.min(attempt - 1, 3), 8L);
        return baseBackoffMs * factor;
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
}
