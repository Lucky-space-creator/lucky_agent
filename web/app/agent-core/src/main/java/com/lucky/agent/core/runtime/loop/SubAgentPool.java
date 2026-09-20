package com.lucky.agent.core.runtime.loop;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子代理执行池：基于 Java 21 虚拟线程，限制并发度与嵌套深度。
 *
 * <p>要点：</p>
 * <ul>
 *   <li><b>虚拟线程</b>：{@code newVirtualThreadPerTaskExecutor}，子代理各占一条虚拟线程，阻塞等待不浪费平台线程。</li>
 *   <li><b>背压</b>：{@link Semaphore} 限制在途子代理数（默认 4），避免瞬间打爆模型/磁盘。</li>
 *   <li><b>深度限制</b>：限制子代理派生层级，防止无限递归。</li>
 * </ul>
 */
public class SubAgentPool implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final int maxDepth;

    public SubAgentPool(int maxConcurrency, int maxDepth) {
        this.permits = new Semaphore(Math.max(1, maxConcurrency));
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 提交一个子代理任务并等待结果（带超时）。
     *
     * @param depth     当前嵌套深度（根为 0）
     * @param timeoutMs 超时（毫秒，&le;0 表示不限）
     */
    public <T> T submit(int depth, long timeoutMs, Callable<T> task) {
        if (depth > maxDepth) {
            throw new IllegalStateException("子代理嵌套深度超限: " + depth + " > " + maxDepth);
        }
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            Future<T> future = executor.submit(task);
            return (timeoutMs > 0) ? future.get(timeoutMs, TimeUnit.MILLISECONDS) : future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("子代理执行被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("子代理执行失败: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("子代理执行超时（" + timeoutMs + "ms）", e);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
