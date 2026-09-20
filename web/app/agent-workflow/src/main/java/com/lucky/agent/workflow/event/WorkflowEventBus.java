package com.lucky.agent.workflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 轻量事件总线：引擎在关键节点发布 {@link WorkflowEvent}，
 * 订阅者（SSE 端点、审计、指标）按需消费。
 * <p>使用 {@link CopyOnWriteArrayList} 保证发布与订阅的线程安全；单个订阅者异常不影响其他订阅者。</p>
 */
public class WorkflowEventBus {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventBus.class);

    private final List<Consumer<WorkflowEvent>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<WorkflowEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(Consumer<WorkflowEvent> listener) {
        listeners.remove(listener);
    }

    public void publish(WorkflowEvent event) {
        for (Consumer<WorkflowEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (Exception e) {
                log.warn("工作流事件订阅者处理异常（已忽略）: {}", e.getMessage());
            }
        }
    }

    public int subscriberCount() {
        return listeners.size();
    }
}
