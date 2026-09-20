package com.lucky.agent.core.runtime.loop;

import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.core.repository.SubAgentResult;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.ExecutionStatus;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.util.subagent.SubAgentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于既有 {@link SubAgentExecutor} 的子代理启动器：以 Java 21 虚拟线程执行、受并发与深度双重约束。
 *
 * <p>三重硬约束（防止「递归派发失控」这一已知缺口）：</p>
 * <ol>
 *   <li><b>深度</b>：{@code depth >= maxDepth} 直接拒绝，不再派发；</li>
 *   <li><b>单轮数量</b>：{@code subagentSpawnCount} 超过上限即拒绝，避免一次拆解刷出大量子代理；</li>
 *   <li><b>并发与超时</b>：由 {@link SubAgentPool} 的信号量与超时兜底。</li>
 * </ol>
 */
public class ExecutorSubAgentLauncher implements SubAgentLauncher {

    private static final Logger log = LoggerFactory.getLogger(ExecutorSubAgentLauncher.class);

    /** 单轮已派发子代理数（运行时属性键）。 */
    public static final String ATTR_SPAWN_COUNT = "subagentSpawnCount";

    private final SubAgentExecutor executor;
    private final SubAgentPool pool;
    private final int maxDepth;
    private final int maxSpawnPerRun;
    private final long timeoutMs;
    private final AtomicInteger sequence = new AtomicInteger();

    public ExecutorSubAgentLauncher(SubAgentExecutor executor, SubAgentPool pool,
                                    int maxDepth, int maxSpawnPerRun, long timeoutMs) {
        this.executor = executor;
        this.pool = pool;
        this.maxDepth = Math.max(1, maxDepth);
        this.maxSpawnPerRun = Math.max(1, maxSpawnPerRun);
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 300_000L;
    }

    @Override
    public ExecutionResult launch(String task, RuntimeContext ctx, int depth) {
        if (executor == null) {
            return ExecutionResult.failure("子代理执行器未启用（core.subagent-enabled=false），无法派发子任务");
        }
        if (depth >= maxDepth) {
            log.warn("[subagent] 拒绝派发：深度 {} 已达上限 {}", depth, maxDepth);
            return ExecutionResult.failure("子代理嵌套深度超限（" + depth + " >= " + maxDepth + "），已拒绝派发");
        }
        int launched = bump(ctx);
        if (launched > maxSpawnPerRun) {
            log.warn("[subagent] 拒绝派发：本轮已派发 {} 个，超过上限 {}", launched, maxSpawnPerRun);
            return ExecutionResult.failure("本轮子代理数量超限（" + maxSpawnPerRun + "），已拒绝派发");
        }

        String id = "sub-" + sequence.incrementAndGet();
        SubAgentSpec spec = SubAgentSpec.builder()
                .id(id)
                .name(id)
                .description("由主循环通过 spawn_sub_agent 工具派发的隔离子任务")
                .summaryOnly(true)
                .build();

        try {
            SubAgentResult result = pool.submit(depth, timeoutMs,
                    () -> executor.execute(spec, task, ctx.workspaceId(), ctx.sessionId())
                            .block(Duration.ofMillis(timeoutMs)));
            if (result == null) {
                return ExecutionResult.failure("子代理无返回结果");
            }
            ExecutionResult outcome = new ExecutionResult(
                    result.success() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED,
                    result.summary(), java.util.List.of(),
                    result.success() ? null : result.summary(),
                    com.lucky.agent.core.runtime.contract.ExecutionMetrics.empty(),
                    ctx.trace() == null ? null : ctx.trace().child(),
                    java.util.Map.of("subAgentId", result.subAgentId(), "depth", depth));
            log.info("[subagent] {} 完成 success={} depth={}", id, result.success(), depth);
            return outcome;
        } catch (RuntimeException e) {
            log.warn("[subagent] {} 执行失败: {}", id, e.getMessage());
            return ExecutionResult.failure("子代理执行失败: " + e.getMessage());
        }
    }

    private int bump(RuntimeContext ctx) {
        Object current = ctx.attribute(ATTR_SPAWN_COUNT);
        int next = ((current instanceof Number n) ? n.intValue() : 0) + 1;
        ctx.attribute(ATTR_SPAWN_COUNT, next);
        return next;
    }
}
