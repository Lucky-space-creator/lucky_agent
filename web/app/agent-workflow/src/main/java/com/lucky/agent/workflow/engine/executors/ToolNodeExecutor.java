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
 *
 * <p>config: {@code toolName}（兼容 {@code tool}）；可选 {@code params}（JSON 对象，工具入参基线）。
 * 输出：工具返回值 + {@code tool} 名。</p>
 *
 * <p><b>入参合并顺序：</b>{@code config.params} 作为基线，其后叠加节点输入作用域
 * （由 {@code InputMapping} 从上游/全局解析而来）。同名键以<b>上游注入为准</b> ——
 * 这样画布上配置的固定参数充当默认值，而流程运行时的动态数据可以覆盖它。</p>
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

            Map<String, Object> args = new LinkedHashMap<>();
            Object configured = node.config("params");
            if (configured instanceof Map<?, ?> params) {
                params.forEach((k, v) -> {
                    if (k != null) {
                        args.put(String.valueOf(k), v);
                    }
                });
            } else if (configured != null) {
                return NodeResult.failure("工具节点 config.params 需为 JSON 对象: " + node.id());
            }
            args.putAll(context.input().asMap());

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
