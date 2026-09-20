package com.lucky.agent.workflow.domain;

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
