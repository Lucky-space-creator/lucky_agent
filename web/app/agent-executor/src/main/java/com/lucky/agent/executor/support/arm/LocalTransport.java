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
import java.util.ArrayList;
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

        // EXEC 走沙箱：先过命令规则链（deny > ask > allow）+ OS 沙箱红线。
        // 此前这里只查红线黑名单，从不调用 evaluateCommand —— 于是内置
        // deny-destructive-cmds 与用户自定义命令规则在执行臂侧全部失效，
        // 任何绕过 PermissionMiddleware 的入口（CLI `!`、CommandVerifier、
        // 工作流沙箱、技能沙箱）都等于裸奔（台账 #4）。
        if (op.opType() == FileOp.OpType.EXEC) {
            String command = op.args() == null || op.args().get("command") == null
                    ? null : String.valueOf(op.args().get("command"));
            if (command == null || command.isBlank()) {
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "缺少命令参数 command"));
            }
            if (evaluator.commandViolatesRedLine(command)) {
                log.warn("命令触及 OS 沙箱红线，已拦截：wid={} command={}", op.workspaceId(), command);
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "命令触及安全红线，已拦截"));
            }
            PermissionDecision commandDecision =
                    evaluator.evaluateCommand(command, level, ruleCache.current());
            if (commandDecision == PermissionDecision.DENY) {
                log.warn("命令被规则链拒绝：wid={} level={} command={}", op.workspaceId(), level, command);
                String reason = level != PermissionLevel.FULL
                        ? "权限拒绝：当前工作空间权限档为「" + level.getLabel() + "」，命令执行需要「全部权限」。"
                        : "权限拒绝：该命令命中安全规则基线（敏感命令 / 用户自定义 deny 规则），调高权限档也不会放行。";
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), reason));
            }
            // ASK 语义是「规则放行但需确认」。执行臂没有交互能力，无法自己弹确认；
            // 走到这里说明档位已是 FULL（非 FULL 在 evaluateCommand 里已判 DENY），
            // 即用户已授权自主执行，故放行 —— 确认环节由上游（框架中间件）承担。
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

        // RENAME 的第二条路径（目标 to）同样必须过边界：此前只校验了源路径，
        // to 为绝对路径或含 ../ 时可把文件移出工作空间（台账 #1）。
        // 台账 #12：目标路径还要进快照清单 —— 否则还原后源文件回来了、目标文件也还在。
        List<String> extraAffected = List.of();
        if (op.opType() == FileOp.OpType.RENAME) {
            Object raw = op.args() == null ? null : op.args().get("to");
            if (!(raw instanceof String to)) {
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), "缺少目标路径参数 to"));
            }
            try {
                Path target = boundaryGuard.guardRenameTarget(workspaceConfig, op.workspaceId(), absPath, to);
                Path wsRoot = workspaceRoot(op).toAbsolutePath().normalize();
                extraAffected = List.of(wsRoot.relativize(target.toAbsolutePath().normalize()).toString());
            } catch (AgentException e) {
                log.warn("重命名目标越界拦截：wid={} from={} to={} reason={}",
                        op.workspaceId(), op.path(), to, e.getMessage());
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), e.getMessage()));
            }
        }

        // DELETE 不得作用于工作空间根本身（台账 #10）：path 为空 / "." 时 absPath 就是工作空间根，
        // 旧实现只拦了「文件系统根」，于是整仓被移入回收站，该会话后续操作全部失败。
        if (op.opType() == FileOp.OpType.DELETE) {
            Path wsRoot;
            try {
                wsRoot = workspaceRoot(op).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(), e.getMessage()));
            }
            if (absPath.toAbsolutePath().normalize().equals(wsRoot)) {
                log.warn("拒绝删除工作空间根：wid={} path={}", op.workspaceId(), op.path());
                return Mono.just(ExecResult.failure(op.opType(), op.requestId(),
                        "禁止删除工作空间根目录"));
            }
        }

        // 写操作：文件锁 + 操作前快照
        if (isWriteOp(op.opType())) {
            String relPath = op.path() == null ? "root" : op.path();
            Path lockFile = fileLockGuard.lock(op.workspaceId(), relPath, 3000);
            try {
                ExecResult snapshotFailure = snapshotBefore(op, extraAffected);
                if (snapshotFailure != null) {
                    return Mono.just(snapshotFailure);
                }
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

    /**
     * 写前快照（台账 #11）。
     *
     * <p>此前失败只 {@code log.warn} 后继续覆盖/删除：磁盘满或 {@code .rollback} 不可写时，
     * 用户以为有回滚点，实际没有；事后 rollback 返回 false，事前零提示。
     * 现在改为 <b>fail-closed</b>：快照建不起来就不写。</p>
     *
     * @param extraAffected 额外的受影响路径（RENAME 的目标，台账 #12）
     * @return 快照失败时的失败结果；成功返回 null
     */
    private ExecResult snapshotBefore(FileOp op, List<String> extraAffected) {
        List<String> affected = new ArrayList<>();
        if (op.path() != null && !op.path().isBlank()) {
            affected.add(op.path());
        }
        affected.addAll(extraAffected);
        try {
            snapshoter.checkpoint(op.workspaceId(), workspaceRoot(op), affected);
            return null;
        } catch (Exception e) {
            log.error("创建检查点快照失败，已中止写入：wid={} op={} path={}",
                    op.workspaceId(), op.opType(), op.path(), e);
            return ExecResult.failure(op.opType(), op.requestId(),
                    "创建回滚快照失败，已中止本次操作（避免「以为有回滚点，实际没有」）：" + e.getMessage());
        }
    }

    private Path workspaceRoot(FileOp op) {
        return Path.of(workspaceConfig.physicalPathOf(op.workspaceId())
                .orElseThrow(() -> new AgentException("WORKSPACE_NOT_FOUND", "工作空间未注册：" + op.workspaceId())));
    }
}
