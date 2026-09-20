package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.MappingEvaluator;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;
import com.lucky.agent.workflow.engine.TemplateRenderer;

/**
 * LLM 节点：单步确定性调用（非 ReAct 自主循环）。
 * <p>config: {@code prompt}（支持 {@code ${var}} 占位）、可选 {@code system}。
 * 输出：{@code text} / {@code prompt}。</p>
 */
public class LlmNodeExecutor implements NodeExecutor {

    private final TemplateRenderer renderer;

    public LlmNodeExecutor(MappingEvaluator mappingEvaluator) {
        this.renderer = new TemplateRenderer(mappingEvaluator);
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.LLM;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        try {
            NodeDef node = context.node();
            VariableScope scope = context.mergedScope();
            String prompt = renderer.render(node.config("prompt", ""), scope);
            String system = node.config("system", null);
            String fullPrompt = (system == null || system.isBlank()) ? prompt : system + "\n" + prompt;

            String text = context.llmAdapter().complete(fullPrompt, scope.asMap());

            VariableScope out = new VariableScope();
            out.set("text", text);
            out.set("prompt", fullPrompt);
            return NodeResult.success(out);
        } catch (Exception e) {
            return NodeResult.failure("LLM 节点执行失败: " + e.getMessage());
        }
    }
}
