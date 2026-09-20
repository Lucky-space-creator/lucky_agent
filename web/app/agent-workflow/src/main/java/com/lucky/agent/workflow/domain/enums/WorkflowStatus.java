package com.lucky.agent.workflow.domain.enums;

/** 工作流定义/实例的生命周期状态。 */
public enum WorkflowStatus {
    DRAFT,      // 草稿（未启用）
    ENABLED,    // 已启用（可触发）
    DISABLED,   // 已停用
    RUNNING,    // 执行中
    COMPLETED,  // 执行完成
    FAILED,     // 执行失败
    SUSPENDED   // 挂起（人工确认/等待外部事件）
}
