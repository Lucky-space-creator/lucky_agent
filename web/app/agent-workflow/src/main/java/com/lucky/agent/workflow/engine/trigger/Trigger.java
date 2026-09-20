package com.lucky.agent.workflow.engine.trigger;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.enums.TriggerType;

/**
 * 触发器抽象（可复用接口）：把「何时执行工作流」与「如何执行」解耦。
 * <p>新增触发方式（Webhook、消息队列、事件总线等）只需实现本接口并注册到调度器。</p>
 */
public interface Trigger {

    /** 本触发器支持的触发类型。 */
    boolean supports(TriggerType type);

    /** 触发器名称。 */
    String name();

    /**
     * 为某工作流注册触发。
     *
     * @param definition 工作流定义
     * @param task       被触发时执行的动作
     */
    void schedule(WorkflowDef definition, Runnable task);

    /** 取消某工作流的触发。 */
    void cancel(String workflowId);

    /** 释放资源。 */
    default void shutdown() {
    }
}
