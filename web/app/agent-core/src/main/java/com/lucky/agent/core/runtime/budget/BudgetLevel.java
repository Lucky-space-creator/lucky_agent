package com.lucky.agent.core.runtime.budget;

/** 分层预算级别：全局 → 任务 → 步骤 → 子代理（每级独立超时/重试/token 限制）。 */
public enum BudgetLevel {
    GLOBAL,
    TASK,
    STEP,
    SUBAGENT
}
