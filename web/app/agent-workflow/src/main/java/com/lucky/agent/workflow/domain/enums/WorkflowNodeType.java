package com.lucky.agent.workflow.domain.enums;

/**
 * 流程节点类型。
 * <ul>
 *   <li>START / END：流程入口/出口（每个工作流有且仅有一个 START，至少一个 END）。</li>
 *   <li>LLM / TOOL / CONDITION / CODE：原子组件（对应待办.md 工作流模块设计）。</li>
 *   <li>SUBFLOW：组合组件（子流程，可挂一个子工作流或子 Agent 规格）。</li>
 * </ul>
 */
public enum WorkflowNodeType {
    START,
    END,
    LLM,
    TOOL,
    CONDITION,
    CODE,
    SUBFLOW
}
