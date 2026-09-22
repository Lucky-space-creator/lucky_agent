package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;

/** 单节点运行实例（可变状态，供引擎在运行期更新）。 */
public class NodeInstance {

    private final String nodeId;
    private final String nodeName;
    private final WorkflowNodeType type;

    private volatile NodeStatus status = NodeStatus.PENDING;
    private VariableScope input = new VariableScope();
    private VariableScope output = new VariableScope();
    private long startedAt;
    private long endedAt;
    private String error;

    public NodeInstance(String nodeId, String nodeName, WorkflowNodeType type) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.type = type;
    }

    /**
     * 反序列化专用构造：从持久化快照还原节点实例（含终态、输入输出与时间戳）。
     * <p>运行期一律走 {@link #NodeInstance(String, String, WorkflowNodeType)}，
     * 本构造仅由仓储在载入历史实例时使用，勿在引擎链路中调用。</p>
     */
    @JsonCreator
    public NodeInstance(@JsonProperty("nodeId") String nodeId,
                        @JsonProperty("nodeName") String nodeName,
                        @JsonProperty("type") WorkflowNodeType type,
                        @JsonProperty("status") NodeStatus status,
                        @JsonProperty("input") VariableScope input,
                        @JsonProperty("output") VariableScope output,
                        @JsonProperty("startedAt") long startedAt,
                        @JsonProperty("endedAt") long endedAt,
                        @JsonProperty("error") String error) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.type = type;
        this.status = (status == null) ? NodeStatus.PENDING : status;
        this.input = (input == null) ? new VariableScope() : input;
        this.output = (output == null) ? new VariableScope() : output;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.error = error;
    }

    public void markRunning() {
        this.status = NodeStatus.RUNNING;
        this.startedAt = System.currentTimeMillis();
    }

    public void markCompleted(VariableScope output) {
        this.status = NodeStatus.COMPLETED;
        this.output = (output == null) ? new VariableScope() : output;
        this.endedAt = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = NodeStatus.FAILED;
        this.error = error;
        this.endedAt = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = NodeStatus.SKIPPED;
        this.endedAt = System.currentTimeMillis();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public WorkflowNodeType getType() {
        return type;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public VariableScope getInput() {
        return input;
    }

    public void setInput(VariableScope input) {
        this.input = input;
    }

    public VariableScope getOutput() {
        return output;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public String getError() {
        return error;
    }

    public long durationMs() {
        return (endedAt > 0 ? endedAt : System.currentTimeMillis()) - startedAt;
    }
}
