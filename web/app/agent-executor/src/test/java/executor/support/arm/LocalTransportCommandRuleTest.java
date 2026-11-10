package executor.support.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.support.arm.BoundaryGuard;
import com.lucky.agent.executor.support.arm.CmdSandbox;
import com.lucky.agent.executor.support.arm.FileExecutor;
import com.lucky.agent.executor.support.arm.IntegrityChecker;
import com.lucky.agent.executor.support.arm.LocalTransport;
import com.lucky.agent.executor.support.arm.PermissionRuleCache;
import com.lucky.agent.executor.support.rollback.CheckpointSnapshoter;
import com.lucky.agent.executor.support.rollback.Compensator;
import com.lucky.agent.executor.support.rollback.SnapshotIndex;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.permission.support.guard.DangerousOpDetector;
import com.lucky.agent.permission.support.guard.OsSandbox;
import com.lucky.agent.permission.support.guard.PermissionCircuitBreaker;
import com.lucky.agent.permission.support.guard.PermissionOverrideStore;
import com.lucky.agent.permission.support.guard.SymlinkResolver;
import com.lucky.agent.permission.support.rules.PermissionChain;
import com.lucky.agent.permission.support.rules.PermissionEvaluator;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行臂 EXEC 必须走命令规则链（台账 #4）。
 *
 * <p>修复前 {@code LocalTransport} 对 EXEC 只调 {@code commandViolatesRedLine}
 * （OS 黑名单），<b>从不调用</b> {@code evaluateCommand} —— 内置 {@code deny-destructive-cmds}
 * 与用户自定义命令规则在执行臂侧全部失效。本测试用「自定义 deny 规则」钉死这条链，
 * 而不是去撞内置黑名单（那只能证明红线还在，证明不了规则链生效）。</p>
 */
class LocalTransportCommandRuleTest {

    @Test
    void execIsBlockedByCommandRuleChain() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        PermissionRule denyRule = new PermissionRule().id("deny-git").priority(10)
                .type(PermissionRule.RuleType.COMMAND)
                .matcher(new PermissionRule.Matcher("regex:^git\\s+status", null))
                .action(PermissionRule.RuleAction.DENY);

        LocalTransport transport = transport(root, List.of(denyRule));

        // 刻意用无害命令：若被拦只能是因为规则链（不是 OS 红线黑名单），断言才有区分力
        ExecResult r = transport.execute(execOp("git status"), PermissionDecision.ALLOW).block();

        assertNotNull(r);
        assertFalse(r.ok(), "命中 deny 规则必须失败");
        assertTrue(r.error().contains("规则基线") || r.error().contains("权限拒绝"), r.error());
        assertNull(r.content(), "被拦截的命令不得真正执行（不应有输出）");
    }

    @Test
    void execPassesWhenNoRuleBlocks() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        LocalTransport transport = transport(root, List.of());

        ExecResult r = transport.execute(execOp("echo rule-chain-ok"), PermissionDecision.ALLOW).block();

        assertNotNull(r);
        assertTrue(r.ok(), "无规则拦截时应正常执行，实际：" + r.error());
        assertNotNull(r.content());
        assertTrue(r.content().contains("rule-chain-ok"), r.content());
    }

    @Test
    void deleteOfWorkspaceRootIsRejected() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        LocalTransport transport = transport(root, List.of());

        // 台账 #10：path 为空 / "." 时 absPath 就是工作空间根，旧实现只拦了文件系统根
        for (String path : List.of("", ".", "./")) {
            ExecResult r = transport.execute(
                    new FileOp(FileOp.OpType.DELETE).workspaceId("wid").path(path),
                    PermissionDecision.ALLOW).block();
            assertNotNull(r, "path=" + path);
            assertFalse(r.ok(), "删除工作空间根必须被拒：path=" + path);
            assertTrue(r.error().contains("工作空间根"), r.error());
        }
        assertTrue(Files.isDirectory(root), "工作空间目录必须仍然存在");
    }

    private static FileOp execOp(String command) {
        return new FileOp(FileOp.OpType.EXEC).workspaceId("wid").args(Map.of("command", command));
    }

    private static LocalTransport transport(Path root, List<PermissionRule> rules) throws Exception {
        WorkspaceDirs dirs = dirsAt(root.getParent().resolve("framework-" + System.nanoTime()));
        WorkspaceConfig config = workspaceConfig(root);
        PermissionEvaluator evaluator = new PermissionEvaluator(
                new PermissionChain(), new DangerousOpDetector(), new PermissionOverrideStore(),
                new SymlinkResolver(), new OsSandbox(), new PermissionCircuitBreaker(50));
        PermissionService permissionService = permissionService(rules);
        PermissionRuleCache ruleCache = new PermissionRuleCache();
        FileExecutor fileExecutor = new FileExecutor(
                new com.lucky.agent.common.concurrent.FileLockGuard(dirs.tmpDir()), dirs);

        return new LocalTransport(config, permissionService, evaluator,
                new BoundaryGuard(), fileExecutor, new CmdSandbox(true, 20),
                new CheckpointSnapshoter(dirs, new SnapshotIndex(new ObjectMapper())),
                new Compensator(dirs), new IntegrityChecker(false), ruleCache, dirs);
    }

    private static PermissionService permissionService(List<PermissionRule> rules) {
        return new PermissionService() {
            @Override
            public PermissionDecision evaluateFileOp(FileOp op, String workspaceId) {
                return PermissionDecision.ALLOW;
            }

            @Override
            public PermissionDecision evaluateCommand(String command, String workspaceId) {
                return PermissionDecision.DEFER;
            }

            @Override
            public List<PermissionRule> loadRules() {
                return rules;
            }

            @Override
            public void saveRules(List<PermissionRule> ignored) {
                // 测试桩
            }

            @Override
            public List<PermissionRule> currentRules() {
                return rules;
            }

            @Override
            public void allowOnce(FileOp op) {
                // 测试桩
            }
        };
    }

    private static WorkspaceConfig workspaceConfig(Path root) {
        return new WorkspaceConfig() {
            @Override
            public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
                return Optional.of(PermissionLevel.FULL);
            }

            @Override
            public Optional<String> physicalPathOf(String workspaceId) {
                return Optional.of(root.toAbsolutePath().toString());
            }

            @Override
            public Optional<Workspace> getWorkspace(String workspaceId) {
                return Optional.of(Workspace.of(workspaceId, "t", root.toAbsolutePath().toString(),
                        PermissionLevel.FULL, Instant.now()));
            }

            @Override
            public List<Workspace> listWorkspaces() {
                return List.of(Workspace.of("wid", "t", root.toAbsolutePath().toString(),
                        PermissionLevel.FULL, Instant.now()));
            }
        };
    }

    private static WorkspaceDirs dirsAt(Path root) throws Exception {
        WorkspaceDirs dirs = new WorkspaceDirs();
        set(dirs, "frameworkRoot", root.toString());
        set(dirs, "workspaceRoot", root.resolve("ws").toString());
        dirs.init();
        return dirs;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
