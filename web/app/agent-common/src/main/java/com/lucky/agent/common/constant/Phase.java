package com.lucky.agent.common.constant;

/**
 * Agent 执行阶段枚举（PLAN / ACT / ASK）。
 *
 * <p>三个阶段不是三个独立循环，而是同一个 REACT 引擎的三种运行模式，
 * 仅系统提示 / 目标 / 终止条件不同，避免双层嵌套循环失控。</p>
 */
public enum Phase {

    /** 规划阶段：只产出可执行计划，不执行。 */
    PLAN,

    /** 执行阶段：按计划逐步执行单步，调用工具并观察结果。 */
    ACT,

    /** 挂起阶段：信息不足 / 需用户决策 / 危险操作确认时向用户提问，等待输入续跑。 */
    ASK
}
