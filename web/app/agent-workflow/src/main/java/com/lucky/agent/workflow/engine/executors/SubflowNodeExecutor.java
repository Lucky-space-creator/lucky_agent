package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;
import com.lucky.agent.workflow.engine.SubflowInvoker;

/**
 * 子流程节点（组合组件）：内嵌执行另一个工作流定义。
 * <p>config: {@code subWorkflowId}。输出：子实例 {@code instanceId} / {@code status} / 子流程变量。</p>
 */
public class SubflowNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.SUBFLOW;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        NodeDef node = context.node();
        String subId = node.config("subWorkflowId", node.config("workflowId", ""));
        if (subId.isBlank()) {
            return NodeResult.failure("子流程节点未配置 subWorkflowId: " + node.id());
        }
        SubflowInvoker invoker = context.subflowInvoker();
        if (invoker == null) {
            return NodeResult.failure("当前引擎未提供子流程调用器（SubflowInvoker）");
        }
        try {
            WorkflowInstance sub = invoker.invoke(subId, context.input());
            VariableScope out = new VariableScope();
            out.set("instanceId", sub.getInstanceId());
            out.set("status", sub.getStatus().name());
            out.set("variables", sub.getVariables().snapshot().asMap());
            if (sub.getStatus() == WorkflowStatus.FAILED) {
                return new NodeResult(com.lucky.agent.workflow.domain.enums.NodeStatus.FAILED, out,
                        "子流程执行失败: " + sub.getError());
            }
            return NodeResult.success(out);
        } catch (Exception e) {
            return NodeResult.failure("子流程调用异常: " + e.getMessage());
        }
    }
}
