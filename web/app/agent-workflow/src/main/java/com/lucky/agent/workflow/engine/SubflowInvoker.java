package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowInstance;

/**
 * 子流程调用器：由引擎实现，供 SUBFLOW 节点回调（避免执行器反向依赖引擎造成循环耦合）。
 */
@FunctionalInterface
public interface SubflowInvoker {

    /**
     * 以给定输入触发一个子工作流并等待其完成。
     *
     * @param subWorkflowId 子工作流定义 ID
     * @param inputs        初始变量
     * @return 子流程运行实例
     */
    WorkflowInstance invoke(String subWorkflowId, VariableScope inputs);
}
