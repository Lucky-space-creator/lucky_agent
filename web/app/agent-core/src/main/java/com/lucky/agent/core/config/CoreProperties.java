package com.lucky.agent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-core 配置项。
 *
 * @param planMaxSteps      PLAN 阶段步数上限
 * @param actMaxSteps       ACT 阶段步数上限
 * @param runMaxTurns       每次循环边界检查的回合上限
 * @param runMaxBudget      token 预算硬门槛（-1 不设限）
 * @param subagentEnabled   默认单 Agent，按需启用多 Agent
 * @param subagentMaxConcurrency 子代理并行上限
 * @param subagentTaskTimeoutSec  子任务超时
 * @param orchestratorMaxIterations 编排主回环最大迭代次数（安全阀，超过强制结束）
 * @param orchestratorMaxRetries    子任务失败局部重试上限（超限提前终止并告知用户）
 * @param orchestratorMode          核心编排器实现选择：reactor（默认，REACT 主回环）/ langgraph（LangGraph4j 状态图）
 * @param verificationEnabled       是否启用客观验证器（false 时全部走模型主观判定）
 * @param verificationTimeoutSec    单个客观验证器超时（秒）
 * @param retryBackoffMs            子任务局部重试的基础退避毫秒数（指数退避）
 * @param earlyStopConfidenceThreshold 早停置信阈值：客观验证通过率低于该值转 ASK 让用户决策
 */
@ConfigurationProperties(prefix = "core")
public record CoreProperties(
        int planMaxSteps,
        int actMaxSteps,
        int runMaxTurns,
        long runMaxBudget,
        boolean subagentEnabled,
        int subagentMaxConcurrency,
        long subagentTaskTimeoutSec,
        int orchestratorMaxIterations,
        int orchestratorMaxRetries,
        String orchestratorMode,
        Boolean verificationEnabled,
        long verificationTimeoutSec,
        long retryBackoffMs,
        double earlyStopConfidenceThreshold) {

    public CoreProperties {
        if (planMaxSteps <= 0) {
            planMaxSteps = 12;
        }
        if (actMaxSteps <= 0) {
            actMaxSteps = 30;
        }
        if (runMaxTurns <= 0) {
            runMaxTurns = 30;
        }
        if (runMaxBudget == 0) {
            runMaxBudget = -1;
        }
        if (subagentMaxConcurrency <= 0) {
            subagentMaxConcurrency = 4;
        }
        if (subagentTaskTimeoutSec <= 0) {
            subagentTaskTimeoutSec = 300;
        }
        if (orchestratorMaxIterations <= 0) {
            orchestratorMaxIterations = 3;
        }
        if (orchestratorMaxRetries <= 0) {
            orchestratorMaxRetries = 2;
        }
        // 核心编排器实现：reactor（默认） / langgraph；非法值回退到 reactor
        if (orchestratorMode == null || (!orchestratorMode.equals("reactor")
                && !orchestratorMode.equals("langgraph"))) {
            orchestratorMode = "reactor";
        }
        // 客观验证默认开启（Boolean 包装类型：未配置时取 true）
        if (verificationEnabled == null) {
            verificationEnabled = Boolean.TRUE;
        }
        if (verificationTimeoutSec <= 0) {
            verificationTimeoutSec = 120;
        }
        if (retryBackoffMs < 0) {
            retryBackoffMs = 500;
        }
        if (earlyStopConfidenceThreshold < 0 || earlyStopConfidenceThreshold > 1) {
            earlyStopConfidenceThreshold = 0.3;
        }
    }
}
