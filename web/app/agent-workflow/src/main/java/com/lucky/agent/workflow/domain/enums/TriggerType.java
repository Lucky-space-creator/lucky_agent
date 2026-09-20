package com.lucky.agent.workflow.domain.enums;

/**
 * 工作流触发类型。
 * <ul>
 *   <li>MANUAL：由 API/UI 手动触发。</li>
 *   <li>INTERVAL：固定周期触发（基于 ScheduledExecutorService）。</li>
 *   <li>CRON：未来扩展点（完整 cron 解析可在此接入）。</li>
 * </ul>
 */
public enum TriggerType {
    MANUAL,
    INTERVAL,
    CRON
}
