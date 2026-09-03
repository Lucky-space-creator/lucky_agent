package com.lucky.agent.core.subagent;

/**
 * 子代理调度器运行状态快照（供状态面板查询）。
 *
 * @param activeRuns      当前进行中的调度批次数
 * @param runningSubAgents 当前在执行的子代理数
 * @param maxConcurrency  配置的并行上限
 * @param totalCompleted  累计成功完成的子代理数
 * @param totalFailed     累计失败的子代理数
 */
public record TaskSchedulerStatus(int activeRuns, int runningSubAgents, int maxConcurrency,
                                  long totalCompleted, long totalFailed) {
}
