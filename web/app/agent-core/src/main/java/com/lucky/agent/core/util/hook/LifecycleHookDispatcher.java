package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.contract.LifecycleHook;
import com.lucky.agent.common.dto.HookEvent;

import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部 Hook 事件分发器（D13）�? *
 * <p>多个 Hook 按注册顺序执行，任一 DENY 立即阻断（deny-wins）；内部 Hook 默认不阻塞�? * 外部 Shell/Webhook/MCP Hook 后续接入时沿用同一决策结构�?/p>
 */

@Slf4j
public class LifecycleHookDispatcher {

    
    private final List<LifecycleHook> hooks;

    public LifecycleHookDispatcher(List<LifecycleHook> hooks) {
        this.hooks = hooks == null ? List.of()
                : hooks.stream().sorted(Comparator.comparingInt(LifecycleHook::order)).toList();
    }

    /**
     * 分发事件并返回裁决结果（deny-wins）�?     *
     * @param event 生命周期事件
     * @return 最终事件（携带裁决结果�?     */
    public HookEvent dispatch(HookEvent event) {
        for (LifecycleHook hook : hooks) {
            try {
                HookEvent result = hook.onEvent(event);
                if (result != null) {
                    event = result;
                }
                if (event.isDenied()) {
                    log.info("Hook 阻断：{} reason={}", event.hookEventName(), event.decisionReason());
                    return event;
                }
            } catch (Exception e) {
                log.warn("Hook 执行异常：{}", event.hookEventName(), e);
            }
        }
        return event;
    }

    /** 触发不阻塞事件（fire-and-forget，如 SessionStart/Stop 记录）�?*/
    public void fire(HookEvent event) {
        dispatch(event);
    }
}
