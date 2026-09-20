package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.dto.HookEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP Tool 外部 Hook：将事件转发到 MCP 服务工具执行并解析裁决。
 * <p>MCP 模块（Phase 3）未接入前保持放行，仅记录待办；接入后在此实现
 * {@code serverName.toolName} 的工具调用并复用 {@link #applyDecision} 解析。</p>
 */
@Slf4j
public class McpToolHook extends ExternalHook {

    public McpToolHook(ExternalHookConfig config) {
        super(config);
    }

    @Override
    protected HookEvent invoke(HookEvent event) {
        log.warn("MCP Tool Hook 尚未接入（MCP 模块 Phase 3），按放行处理：server={} tool={}",
                config.serverName(), config.toolName());
        return event;
    }
}
