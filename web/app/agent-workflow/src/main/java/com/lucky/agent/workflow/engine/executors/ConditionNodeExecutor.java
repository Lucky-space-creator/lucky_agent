package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;

/**
 * 条件节点：对表达式求值并输出布尔结果。
 * <p>config: {@code condition}。输出：{@code result}（boolean）/ {@code condition}。
 * 实际路由由该节点出边的 {@code condition} 决定，条件节点可用于显式表达判断点并产出 {@code result} 供后续边引用。</p>
 */
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.CONDITION;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        try {
            NodeDef node = context.node();
            String condition = node.config("condition", "");
            boolean result = context.conditionEvaluator().evaluate(condition, context.mergedScope());

            VariableScope out = new VariableScope();
            out.set("result", result);
            out.set("condition", condition);
            return NodeResult.success(out);
        } catch (Exception e) {
            return NodeResult.failure("条件节点求值失败: " + e.getMessage());
        }
    }
}
