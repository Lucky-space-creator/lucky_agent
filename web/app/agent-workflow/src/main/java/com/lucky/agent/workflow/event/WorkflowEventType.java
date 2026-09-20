package com.lucky.agent.workflow.event;

/** 工作流执行过程事件类型（用于 SSE 监控与审计埋点）。 */
public enum WorkflowEventType {
    WORKFLOW_STARTED,
    NODE_STARTED,
    NODE_COMPLETED,
    NODE_FAILED,
    NODE_SKIPPED,
    VARIABLE_UPDATED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED
}
