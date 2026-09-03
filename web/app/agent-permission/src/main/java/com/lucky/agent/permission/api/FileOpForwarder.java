package com.lucky.agent.permission.api;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import reactor.core.publisher.Mono;

/**
 * Web 文件操作转发契约。
 *
 * <p>前端 → 框架文件服务（本转发器）→ 下发指令给本机执行臂 → 执行臂在授权目录执行 →
 * 结果（仅元数据）回传。框架服务端不接触文件内容，只转发指令与元数据。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "file-op-forwarder")
public interface FileOpForwarder {

    /**
     * 转发文件操作指令给执行臂。
     *
     * @param op 文件操作指令
     * @return 执行结果（仅元数据）
     */
    Mono<ExecResult> forward(FileOp op);
}
