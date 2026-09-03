package com.lucky.agent.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-executor 配置项（§3.0 / §四）。
 *
 * @param transport             ExecutionTransport 实现：local（进程内/IPC），远端模式切换 wss
 * @param boundaryStrict        realpath 硬边界是否严格开启
 * @param sandboxEnabled        命令执行沙箱（默认关闭）
 * @param cmdTimeoutSec         命令执行超时秒数
 * @param integrityStrict       执行臂完整性严格校验
 */
@ConfigurationProperties(prefix = "executor")
public record ExecutorProperties(
        String transport,
        boolean boundaryStrict,
        boolean sandboxEnabled,
        long cmdTimeoutSec,
        boolean integrityStrict) {

    public ExecutorProperties {
        if (transport == null || transport.isBlank()) {
            transport = "local";
        }
        if (cmdTimeoutSec <= 0) {
            cmdTimeoutSec = 30;
        }
    }
}
