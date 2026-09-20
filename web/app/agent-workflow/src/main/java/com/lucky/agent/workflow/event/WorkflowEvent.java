package com.lucky.agent.workflow.event;

import java.util.Map;

/**
 * 工作流运行时事件（不可变）。
 *
 * @param instanceId 实例 ID
 * @param workflowId 工作流定义 ID
 * @param nodeId     相关节点 ID（流程级事件可为空）
 * @param type       事件类型
 * @param timestamp  发生时间（毫秒）
 * @param message    人类可读描述
 * @param data       附加数据（变量快照、错误信息等）
 */
public record WorkflowEvent(
        String instanceId,
        String workflowId,
        String nodeId,
        WorkflowEventType type,
        long timestamp,
        String message,
        Map<String, Object> data) {

    public static WorkflowEvent of(String instanceId, String workflowId, String nodeId,
                                   WorkflowEventType type, String message) {
        return new WorkflowEvent(instanceId, workflowId, nodeId, type,
                System.currentTimeMillis(), message, Map.of());
    }

    public static WorkflowEvent of(String instanceId, String workflowId, String nodeId,
                                   WorkflowEventType type, String message, Map<String, Object> data) {
        return new WorkflowEvent(instanceId, workflowId, nodeId, type,
                System.currentTimeMillis(), message, data == null ? Map.of() : data);
    }
}
