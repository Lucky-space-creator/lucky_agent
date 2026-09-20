package com.lucky.agent.workflow.domain;

import com.lucky.agent.workflow.domain.enums.WorkflowStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 工作流运行实例：描述某一次具体执行的状态与产出。 */
public class WorkflowInstance {

    private final String instanceId;
    private final String workflowId;
    private final String workflowName;

    private volatile WorkflowStatus status = WorkflowStatus.RUNNING;
    private final VariableScope variables = new VariableScope();
    private final Map<String, NodeInstance> nodeInstances = new ConcurrentHashMap<>();
    private final Map<String, Long> nodeStartOrder = new LinkedHashMap<>();

    private long startedAt = System.currentTimeMillis();
    private long endedAt;
    private String currentNodeId;
    private String error;

    public WorkflowInstance(String workflowId, String workflowName) {
        this.instanceId = UUID.randomUUID().toString();
        this.workflowId = workflowId;
        this.workflowName = workflowName;
    }

    public NodeInstance node(String nodeId, String nodeName, com.lucky.agent.workflow.domain.enums.WorkflowNodeType type) {
        NodeInstance ni = nodeInstances.computeIfAbsent(nodeId,
                k -> new NodeInstance(nodeId, nodeName, type));
        nodeStartOrder.putIfAbsent(nodeId, System.currentTimeMillis());
        return ni;
    }

    public NodeInstance nodeOf(String nodeId) {
        return nodeInstances.get(nodeId);
    }

    public void markCompleted() {
        this.status = WorkflowStatus.COMPLETED;
        this.endedAt = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = WorkflowStatus.FAILED;
        this.error = error;
        this.endedAt = System.currentTimeMillis();
    }

    public void markSuspended() {
        this.status = WorkflowStatus.SUSPENDED;
    }

    /** 由状态机驱动设置状态（校验在 {@code WorkflowStateMachine} 中完成）。 */
    public void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public VariableScope getVariables() {
        return variables;
    }

    public Map<String, NodeInstance> getNodeInstances() {
        return nodeInstances;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public String getError() {
        return error;
    }

    public long durationMs() {
        return (endedAt > 0 ? endedAt : System.currentTimeMillis()) - startedAt;
    }
}
