package com.lucky.agent.common.constant;

/**
 * 权限裁决结果枚举。
 *
 * <p>规则链按 {@code deny > ask > allow} 顺序执行：任一 DENY 立即阻断（deny-wins）；
 * 无规则命中时按工作区权限级别走 ASK 或 ALLOW；DEFER 表示交由下一级（如执行臂）裁决。</p>
 */
public enum PermissionDecision {

    /** 允许执行。 */
    ALLOW,

    /** 拒绝执行（永远胜出，立即阻断）。 */
    DENY,

    /** 需要向用户提问确认后再执行。 */
    ASK,

    /** 本层不裁决，交由下一级（执行臂硬边界）继续判断。 */
    DEFER
}
