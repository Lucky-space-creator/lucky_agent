package com.lucky.agent.workflow.domain.enums;

/** 单节点实例的执行状态。 */
public enum NodeStatus {
    PENDING,   // 等待前驱就绪
    RUNNING,   // 执行中
    COMPLETED, // 成功
    FAILED,    // 失败
    SKIPPED,   // 因前置条件未触发而被跳过
    WAITING    // 等待外部事件/人工确认
}
