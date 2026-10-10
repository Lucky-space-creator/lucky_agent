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
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), denyReason(op, level)));
        }
        if (local == PermissionDecision.ASK) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(), ExecResult.ASK_REQUIRED));
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

    /**
     * 拒绝原因文案（缺陷 D4 的修复点）。
     *
     * <p>此前这里只有「权限拒绝」四个字，用户既无法区分是<b>规则基线</b>拒绝还是
     * <b>权限档不足</b>，也不知道该改什么。实测中 {@code !whoami} 这类无害命令在
     * 「修改文件」档下全部失败且零指引。现在把两类原因分开，并给档位不足补上
     * 「改哪里」的可行动路径。</p>
     *
     * <p>归因依据：{@link PermissionEvaluator#evaluate} 在规则链判 DENY/ASK 时提前返回，
     * 故能走到档位判定就说明原因确定是档位不足；反之则为规则基线拒绝。</p>
     */
    private String denyReason(FileOp op, PermissionLevel level) {
        if (!evaluator.deniedByLevel(op, level)) {
            return "权限拒绝：该操作命中安全规则基线（敏感路径 / 破坏性命令等），与工作空间权限档无关，"
                    + "调高权限档也不会放行。";
        }
        if (op.opType() == FileOp.OpType.EXEC) {
            return "权限拒绝：当前工作空间权限档为「" + level.getLabel() + "」，命令执行需要「全部权限」。"
                    + "请把该工作空间的权限级别改为「全部权限」，或指定一个已设为「全部权限」的工作空间。";
        }
        return "权限拒绝：当前工作空间权限档为「" + level.getLabel()
                + "」，该操作超出档位（写操作需「修改文件」或以上）。";
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
