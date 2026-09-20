package com.lucky.agent.workflow.engine.executors;

import com.lucky.agent.workflow.adapter.CommandResult;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.engine.NodeExecutionContext;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.NodeResult;
import com.lucky.agent.workflow.engine.TemplateRenderer;

/**
 * 代码节点：在沙箱中执行命令/脚本。
 * <p>config: {@code command}（支持 {@code ${var}} 占位）、{@code timeoutMs}、{@code failOnError}（默认 true）。
 * 输出：{@code exitCode} / {@code stdout} / {@code stderr} / {@code success} / {@code timedOut}。</p>
 */
public class CodeNodeExecutor implements NodeExecutor {

    private final TemplateRenderer renderer;

    public CodeNodeExecutor(com.lucky.agent.workflow.engine.MappingEvaluator mappingEvaluator) {
        this.renderer = new TemplateRenderer(mappingEvaluator);
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.CODE;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        try {
            NodeDef node = context.node();
            String command = renderer.render(node.config("command", node.config("script", "")), context.mergedScope());
            long timeout = node.configInt("timeoutMs", 0);
            boolean failOnError = node.configBool("failOnError", true);

            CommandResult cr = context.sandboxAdapter().run(command, timeout);

            VariableScope out = new VariableScope();
            out.set("exitCode", cr.exitCode());
            out.set("stdout", cr.stdout());
            out.set("stderr", cr.stderr());
            out.set("success", cr.success());
            out.set("timedOut", cr.timedOut());

            if (!cr.success() && failOnError) {
                return new NodeResult(com.lucky.agent.workflow.domain.enums.NodeStatus.FAILED, out,
                        "代码节点执行失败: " + (cr.stderr() == null || cr.stderr().isBlank() ? "exit=" + cr.exitCode() : cr.stderr()));
            }
            return NodeResult.success(out);
        } catch (Exception e) {
            return NodeResult.failure("代码节点执行异常: " + e.getMessage());
        }
    }
}
