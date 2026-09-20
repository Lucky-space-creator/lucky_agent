package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;

/** 起始节点：仅作为流程入口，透传输入（种子变量）。 */
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.START;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        VariableScope out = context.input().snapshot();
        out.set("startedAt", System.currentTimeMillis());
        return NodeResult.success(out);
    }
}
