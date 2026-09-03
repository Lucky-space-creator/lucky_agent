package com.lucky.agent.mcp.config;

import com.lucky.agent.common.constant.WorkspaceDirs;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-mcp 配置项。
 *
 * @param userDir          用户自建 MCP 配置目录（相对单根，默认 {@code mcp}）
 * @param heartbeatSec     心跳周期（秒，默认 30）
 * @param healthProbeSec   健康探活周期（秒，默认 60）
 * @param connectTimeoutMs 建立连接超时（毫秒，默认 8000）
 * @param requestTimeoutMs 单次 JSON-RPC 请求超时（毫秒，默认 10000）
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(
        String userDir,
        long heartbeatSec,
        long healthProbeSec,
        long connectTimeoutMs,
        long requestTimeoutMs) {

    public McpProperties {
        if (userDir == null || userDir.isBlank()) {
            userDir = WorkspaceDirs.MCP;
        }
        if (heartbeatSec <= 0) {
            heartbeatSec = 30;
        }
        if (healthProbeSec <= 0) {
            healthProbeSec = 60;
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 8000;
        }
        if (requestTimeoutMs <= 0) {
            requestTimeoutMs = 10000;
        }
    }
}
