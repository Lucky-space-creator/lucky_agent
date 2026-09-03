package com.lucky.agent.common.contract;

import com.lucky.agent.common.dto.HookEvent;

/**
 * 生命周期 Hook 监听器契约。
 *
 * <p>内部 Hook 默认不阻塞；多个 Hook 按注册顺序执行，任一 DENY 立即阻断（deny-wins）。
 * 外部 Shell/Webhook/MCP Hook 后续接入时沿用同一决策结构。</p>
 */
@FunctionalInterface
public interface LifecycleHook {

    /** 触发事件处理，返回可能被修改的事件（携带裁决结果）。 */
    HookEvent onEvent(HookEvent event);

    /** 注册顺序，越小越先执行；默认 0。 */
    default int order() {
        return 0;
    }
}
