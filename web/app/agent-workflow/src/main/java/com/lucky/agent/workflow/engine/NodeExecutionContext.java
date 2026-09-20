package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.event.WorkflowEventBus;

/**
 * 节点执行上下文：执行器所需的全部依赖与数据（只读传递）。
 *
 * @param node               节点定义
 * @param global             工作流全局作用域
 * @param input              当前节点输入作用域（由连接器解析）
 * @param instance           所属工作流实例
 * @param eventBus           事件总线（可发布进度）
 * @param llmAdapter         LLM 适配器
 * @param toolAdapter        工具适配器
 * @param sandboxAdapter     沙箱适配器
 * @param mappingEvaluator   连接器求值器
 * @param conditionEvaluator 条件求值器
 * @param subflowInvoker     子流程调用器（SUBFLOW 节点使用，可为 null）
 */
public record NodeExecutionContext(
        NodeDef node,
        VariableScope global,
        VariableScope input,
        WorkflowInstance instance,
        WorkflowEventBus eventBus,
        LlmAdapter llmAdapter,
        ToolAdapter toolAdapter,
        SandboxAdapter sandboxAdapter,
        MappingEvaluator mappingEvaluator,
        ConditionEvaluator conditionEvaluator,
        SubflowInvoker subflowInvoker) {

    /** 合并后的可读作用域：全局 + 节点输入（输入优先）。 */
    public VariableScope mergedScope() {
        VariableScope merged = global.snapshot();
        merged.merge(input);
        return merged;
    }
}
