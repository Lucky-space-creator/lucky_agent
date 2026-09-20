package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.NodeStatus;

/**
 * 节点执行结果。
 *
 * @param status 节点状态
 * @param output 节点输出作用域
 * @param error  失败信息（成功时为空）
 */
public record NodeResult(NodeStatus status, VariableScope output, String error) {

    public static NodeResult success(VariableScope output) {
        return new NodeResult(NodeStatus.COMPLETED, output == null ? new VariableScope() : output, null);
    }

    public static NodeResult failure(String error) {
        return new NodeResult(NodeStatus.FAILED, new VariableScope(), error);
    }

    public static NodeResult skipped(String reason) {
        return new NodeResult(NodeStatus.SKIPPED, new VariableScope(), reason);
    }

    public boolean isSuccess() {
        return status == NodeStatus.COMPLETED;
    }
}
