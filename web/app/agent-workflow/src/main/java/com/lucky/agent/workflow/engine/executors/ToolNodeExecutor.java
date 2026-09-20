package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具节点：调用外部工具（经 {@code ToolAdapter}）。
 * <p>config: {@code toolName}；参数默认取自节点输入作用域。输出：工具返回值 + {@code tool} 名。</p>
 */
public class ToolNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.TOOL;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        try {
            NodeDef node = context.node();
            String toolName = node.config("toolName", node.config("tool", ""));
            if (toolName.isBlank()) {
                return NodeResult.failure("工具节点未配置 toolName: " + node.id());
            }
            Map<String, Object> args = new LinkedHashMap<>(context.input().asMap());

            Map<String, Object> result = context.toolAdapter().call(toolName, args);
            VariableScope out = new VariableScope();
            out.set("tool", toolName);
            if (result != null) {
                result.forEach(out::set);
            }
            return NodeResult.success(out);
        } catch (Exception e) {
            return NodeResult.failure("工具节点执行失败: " + e.getMessage());
        }
    }
}
