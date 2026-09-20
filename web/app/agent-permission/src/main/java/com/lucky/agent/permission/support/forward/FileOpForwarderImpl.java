package com.lucky.agent.permission.support.forward;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.ExecutionTransport;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.service.FileOpForwarder;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.permission.repository.dto.AuditMeta;
import com.lucky.agent.permission.support.guard.AuditLogger;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Web 文件操作转发实现：裁决 → 审计 → 下发执行臂。
 * <p>框架服务端不接触文件内容，仅转发指令与元数据；执行臂本地重新评估为最终裁决者。</p>
 */

@Slf4j
@Service
public class FileOpForwarderImpl implements FileOpForwarder {

    
    private final PermissionService permissionService;
    private final ExecutionTransport executionTransport;
    private final AuditLogger auditLogger;

    public FileOpForwarderImpl(PermissionService permissionService, ExecutionTransport executionTransport,
                               AuditLogger auditLogger) {
        this.permissionService = permissionService;
        this.executionTransport = executionTransport;
        this.auditLogger = auditLogger;
    }

    @Override
    public Mono<ExecResult> forward(FileOp op) {
        if (op == null || op.opType() == null) {
            return Mono.just(ExecResult.failure(null, "no-op", "操作指令不合法"));
        }
        PermissionDecision decision = permissionService.evaluateFileOp(op, op.workspaceId());
        audit(op, decision);

        if (decision == PermissionDecision.DENY) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "权限拒绝：" + describeDeny(op)));
        }
        if (decision == PermissionDecision.ASK) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "高危操作需用户确认"));
        }
        return executionTransport.execute(op, decision);
    }

    private void audit(FileOp op, PermissionDecision decision) {
        auditLogger.record(new AuditMeta(
                Instant.now().toString(),
                "local-user",
                op.workspaceId(),
                op.opType().name(),
                Map.of("path", op.path(), "decision", decision.name())));
    }

    private String describeDeny(FileOp op) {
        return "操作 " + op.opType() + " 在工作空间 " + op.workspaceId() + " 上未被允许";
    }
}
