package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;

/**
 * 节点执行器（可复用组件接口）。
 * <p>每种 {@link WorkflowNodeType} 对应一个执行器实现；引擎按类型分发。
 * 新增节点类型只需实现本接口并注册，无需改动引擎（对扩展开放、对修改封闭）。</p>
 */
public interface NodeExecutor {

    /** 是否支持该节点类型。 */
    boolean supports(WorkflowNodeType type);

    /** 执行节点，返回结果（不抛异常，失败以 {@link NodeResult#failure} 返回）。 */
    NodeResult execute(NodeExecutionContext context);
}
