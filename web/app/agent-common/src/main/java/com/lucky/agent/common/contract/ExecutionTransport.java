package com.lucky.agent.common.contract;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import reactor.core.publisher.Mono;

/**
 * 执行侧传输契约（契约 §6），本机模式与远端模式共用。
 *
 * <p>Phase 1 使用 {@code LocalTransport}（进程内/本机 IPC），不引入 WSS；
 * 方案 B 时新增 {@code WssTransport}，业务模块不感知传输差异。
 * 执行臂本地重新评估权限规则，框架侧裁决仅作提示，执行臂为最终裁决者。</p>
 */
@Remote(serviceName = "executor")
public interface ExecutionTransport {

    /**
     * 下发文件操作指令给执行臂。
     *
     * @param op       文件操作指令
     * @param decision 框架侧权限裁决（仅作提示，执行臂重新评估）
     * @return 执行结果（仅元数据；本机模式可含 content）
     */
    Mono<ExecResult> execute(FileOp op, PermissionDecision decision);

    /** 探活。 */
    Mono<Boolean> ping();
}
