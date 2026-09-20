package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;

/** 结束节点：标记流程产出。 */
public class EndNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.END;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        VariableScope out = new VariableScope();
        out.set("endedAt", System.currentTimeMillis());
        out.set("payload", context.input().snapshot().asMap());
        return NodeResult.success(out);
    }
}
