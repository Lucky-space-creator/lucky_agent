package com.lucky.agent.core.util.hook;

import java.util.Map;

/**
 * 外部 Hook 配置（契约 §2 扩展：Shell / Webhook / MCP Tool）。
 *
 * <p>配置落盘于 {@code <frameworkRoot>/.config/external-hooks.json}，由
 * {@link ExternalHookManager} 转为 {@link LifecycleHook} 实例接入 deny-wins 决策链。</p>
 *
 * @param id         配置唯一标识（为空时由管理端生成）
 * @param name       展示名称
 * @param type       SHELL / WEBHOOK / MCP_TOOL
 * @param eventName  监听事件名（如 PreToolUse；空串监听全部事件）
 * @param order      注册顺序，越小越先执行
 * @param enabled    是否启用
 * @param command    SHELL：要执行的命令（事件 JSON 经 stdin 传入）
 * @param url        WEBHOOK：回调地址
 * @param method     WEBHOOK：HTTP 方法（默认 POST）
 * @param headers    WEBHOOK：附加请求头
 * @param timeoutSec 超时秒数（默认 10，超时按放行处理）
 * @param serverName MCP_TOOL：MCP 服务名（预留，MCP 模块接入后生效）
 * @param toolName   MCP_TOOL：工具名（预留）
 */
public record ExternalHookConfig(
        String id,
        String name,
        String type,
        String eventName,
        int order,
        boolean enabled,
        String command,
        String url,
        String method,
        Map<String, String> headers,
        int timeoutSec,
        String serverName,
        String toolName) {

    public ExternalHookConfig {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("外部 Hook type 不能为空");
        }
        if (timeoutSec <= 0) {
            timeoutSec = 10;
        }
        if (order < 0) {
            order = 0;
        }
    }

    /** 是否监听该事件名（空串/空值表示监听全部）。 */
    public boolean matches(String eventNameOfEvent) {
        return eventName == null || eventName.isBlank() || eventName.equalsIgnoreCase(eventNameOfEvent);
    }
}
