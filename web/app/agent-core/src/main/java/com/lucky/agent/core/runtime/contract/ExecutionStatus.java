package com.lucky.agent.core.runtime.contract;

/**
 * 统一执行状态：所有执行路径（LLM / 工具 / 步骤 / 子代理 / 验证）共用同一套状态枚举。
 */
public enum ExecutionStatus {
    SUCCESS,           // 执行成功
    FAILED,            // 执行失败（可含错误）
    ASK,               // 需人工确认（权限/信息缺口）
    CANCELLED,         // 用户取消
    BUDGET_EXHAUSTED,  // 预算耗尽（token/时间/重试）
    TIMEOUT            // 超时
}
