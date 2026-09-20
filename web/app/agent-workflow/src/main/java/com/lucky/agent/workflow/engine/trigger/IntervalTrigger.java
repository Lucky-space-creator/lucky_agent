package com.lucky.agent.workflow.engine.trigger;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.enums.TriggerType;
import com.lucky.agent.workflow.exception.WorkflowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 周期触发器：按 {@code TriggerDef.expression}（毫秒周期）固定频率触发。
 * <p>完整 cron 语义（{@code TriggerType.CRON}）作为后续扩展点在此实现。</p>
 */
public class IntervalTrigger implements Trigger {

    private static final Logger log = LoggerFactory.getLogger(IntervalTrigger.class);

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public IntervalTrigger(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public boolean supports(TriggerType type) {
        return type == TriggerType.INTERVAL;
    }

    @Override
    public String name() {
        return "interval";
    }

    @Override
    public void schedule(WorkflowDef definition, Runnable task) {
        long period = parsePeriod(definition);
        cancel(definition.id());
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("周期触发执行失败 workflow={}: {}", definition.id(), e.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);
        futures.put(definition.id(), future);
        log.info("已注册周期触发: workflow={} period={}ms", definition.id(), period);
    }

    @Override
    public void cancel(String workflowId) {
        ScheduledFuture<?> future = futures.remove(workflowId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private long parsePeriod(WorkflowDef definition) {
        String expr = definition.trigger() == null ? null : definition.trigger().expression();
        if (expr == null || expr.isBlank()) {
            throw new WorkflowException("周期触发缺少 expression（毫秒周期）: " + definition.id());
        }
        try {
            long period = Long.parseLong(expr.trim());
            if (period <= 0) {
                throw new WorkflowException("周期触发 expression 必须为正数: " + expr);
            }
            return period;
        } catch (NumberFormatException e) {
            throw new WorkflowException("周期触发 expression 非法: " + expr);
        }
    }
}
