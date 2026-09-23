package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.concurrent.FileLockGuard;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.executor.api.ExecutorClient;
import com.lucky.agent.executor.support.rollback.CheckpointSnapshoter;
import com.lucky.agent.executor.support.rollback.Compensator;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.permission.support.rules.PermissionEvaluator;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1 本机执行臂（进程内传输）。
 * <p>职责链：完整性校验 → 本地权限规则副本重新评估（最终裁决）→ realpath 硬边界 → 文件锁 → 快照 → 文件执行。
 * 框架侧裁决仅作提示，执行臂以本地规则副本为唯一生效源，为权限与越界的最终裁决者。</p>
 */

@Slf4j
@Service
public class LocalTransport implements ExecutorClient {

    
    private final WorkspaceConfig workspaceConfig;
    private final PermissionService permissionService;
    private final PermissionEvaluator evaluator;
    private final BoundaryGuard boundaryGuard;
    private final FileExecutor fileExecutor;
    private final CmdSandbox cmdSandbox;
    private final CheckpointSnapshoter snapshoter;
    private final Compensator compensator;
    private final IntegrityChecker integrityChecker;
    private final PermissionRuleCache ruleCache;
    private final FileLockGuard fileLockGuard;

    public LocalTransport(WorkspaceConfig workspaceConfig, PermissionService permissionService,
                          PermissionEvaluator permissionEvaluator,
                          BoundaryGuard boundaryGuard, FileExecutor fileExecutor, CmdSandbox cmdSandbox,
                          CheckpointSnapshoter snapshoter, Compensator compensator,
                          IntegrityChecker integrityChecker, PermissionRuleCache ruleCache,
                          WorkspaceDirs dirs) {
        this.workspaceConfig = workspaceConfig;
        this.permissionService = permissionService;
        this.evaluator = permissionEvaluator;
        this.boundaryGuard = boundaryGuard;
        this.fileExecutor = fileExecutor;
        this.cmdSandbox = cmdSandbox;
        this.snapshoter = snapshoter;
        this.compensator = compensator;
        this.integrityChecker = integrityChecker;
        this.ruleCache = ruleCache;
        this.fileLockGuard = FileLockGuard.of(dirs);
    }

    @Override
    public Mono<ExecResult> execute(FileOp op, PermissionDecision decision) {
        if (op == null || op.opType() == null) {
            return Mono.just(ExecResult.failure(null, "no-op", "操作指令不合法"));
        }
        if (!integrityChecker.verify(ProcessHandle.current().pid())) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "执行臂完整性校验失败"));
        }
        // 同步框架规则到执行臂本地副本，再以本地副本为唯一生效源重新评估（最终裁决）
        ruleCache.sync(permissionService.currentRules());
        PermissionLevel level = workspaceConfig.permissionLevelOf(op.workspaceId())
                .orElse(PermissionLevel.defaultValue());
        Workspace workspace = workspaceConfig.getWorkspace(op.workspaceId()).orElse(null);
        if (workspace == null) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "工作空间未注册"));
        }
        PermissionDecision local = evaluator.evaluate(op, level, Path.of(workspace.path()), ruleCache.current());
        if (local == PermissionDecision.DENY || decision == PermissionDecision.DENY) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "权限拒绝"));
        }
        if (local == PermissionDecision.ASK) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "高危操作需用户确认"));
        }

        // EXEC 走沙箱：先过 OS 沙箱红线（硬边界，与权限模式解耦，FULL 也绝不跳过）
        if (op.opType() == FileOp.OpType.EXEC) {
            String command = op.args() == null ? null : (String) op.args().get("command");
            if (evaluator.commandViolatesRedLine(command)) {
                log.warn("命令触及 OS 沙箱红线，已拦截：wid={} command={}", op.workspaceId(), command);
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "命令触及安全红线，已拦截"));
            }
            Path root = workspaceRoot(op);
            return Mono.just(cmdSandbox.run(op, root));
        }

        // realpath 硬边界
        final Path absPath;
        try {
            absPath = boundaryGuard.guardWrite(workspaceConfig, op.workspaceId(), op.path());
        } catch (AgentException e) {
            log.warn("越界拦截：wid={} path={} reason={}", op.workspaceId(), op.path(), e.getMessage());
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), e.getMessage()));
        }

        // 写操作：文件锁 + 操作前快照
        if (isWriteOp(op.opType())) {
            String relPath = op.path() == null ? "root" : op.path();
            Path lockFile = fileLockGuard.lock(op.workspaceId(), relPath, 3000);
            try {
                snapshotBefore(op);
                return Mono.just(fileExecutor.execute(op, absPath));
            } finally {
                fileLockGuard.unlock(lockFile);
            }
        }
        return Mono.just(fileExecutor.execute(op, absPath));
    }

    @Override
    public Mono<Boolean> ping() {
        return Mono.just(true);
    }

    private boolean isWriteOp(FileOp.OpType type) {
        return type == FileOp.OpType.WRITE || type == FileOp.OpType.DELETE
                || type == FileOp.OpType.RENAME || type == FileOp.OpType.MKDIR;
    }

    private void snapshotBefore(FileOp op) {
        try {
            List<String> affected = op.path() == null ? List.of() : List.of(op.path());
            snapshoter.checkpoint(op.workspaceId(), workspaceRoot(op), affected);
        } catch (Exception e) {
            log.warn("创建检查点快照失败（不影响主流程）：wid={}", op.workspaceId(), e);
        }
    }

    private Path workspaceRoot(FileOp op) {
        return Path.of(workspaceConfig.physicalPathOf(op.workspaceId())
                .orElseThrow(() -> new AgentException("WORKSPACE_NOT_FOUND", "工作空间未注册：" + op.workspaceId())));
    }
}
