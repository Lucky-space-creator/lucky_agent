package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.contract.LifecycleHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 外部 Hook 工厂：按配置类型创建对应适配器。
 */
@Slf4j
@Component
public class ExternalHookFactory {

    /** 依据配置类型创建适配器；未知类型抛 IllegalArgumentException 由调用方兜底。 */
    public LifecycleHook create(ExternalHookConfig config) {
        switch (config.type().toUpperCase()) {
            case "SHELL":
                return new ShellHook(config);
            case "WEBHOOK":
                return new WebhookHook(config);
            case "MCP_TOOL":
                return new McpToolHook(config);
            default:
                throw new IllegalArgumentException("未知外部 Hook 类型：" + config.type());
        }
    }
}
