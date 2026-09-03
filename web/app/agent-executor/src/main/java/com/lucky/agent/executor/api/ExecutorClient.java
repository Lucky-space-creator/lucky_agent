package com.lucky.agent.executor.api;

import com.lucky.agent.common.contract.ExecutionTransport;

/**
 * 执行臂调用契约（框架服务端侧）。
 *
 * <p>Phase 1 经 {@code ExecutionTransport} 本机进程内/IPC 通信，方案 B 时切换 WSS，
 * 业务模块不感知传输差异。执行臂为权限与越界的最终裁决者。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "executor-client")
public interface ExecutorClient extends ExecutionTransport {
}
