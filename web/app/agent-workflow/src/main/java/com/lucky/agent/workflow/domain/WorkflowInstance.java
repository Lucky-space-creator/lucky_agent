package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * 反序列化专用构造：从持久化快照还原运行实例。
     * <p>运行期一律走 {@link #WorkflowInstance(String, String)}（自动生成 instanceId、status 置 RUNNING）；
     * 本构造仅由仓储在载入历史实例时使用，勿在引擎链路中调用。
     * {@code nodeStartOrder} 属运行期排序辅助结构，不持久化，此处重建为空。</p>
     */
    @JsonCreator
    public WorkflowInstance(@JsonProperty("instanceId") String instanceId,
                            @JsonProperty("workflowId") String workflowId,
                            @JsonProperty("workflowName") String workflowName,
                            @JsonProperty("status") WorkflowStatus status,
                            @JsonProperty("variables") VariableScope variables,
                            @JsonProperty("nodeInstances") Map<String, NodeInstance> nodeInstances,
                            @JsonProperty("startedAt") long startedAt,
                            @JsonProperty("endedAt") long endedAt,
                            @JsonProperty("currentNodeId") String currentNodeId,
                            @JsonProperty("error") String error) {
        this.instanceId = instanceId;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.status = (status == null) ? WorkflowStatus.RUNNING : status;
        if (variables != null) {
            this.variables.merge(variables);
        }
        if (nodeInstances != null) {
            this.nodeInstances.putAll(nodeInstances);
        }
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.currentNodeId = currentNodeId;
        this.error = error;
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
