package executor.support.arm;

import com.lucky.agent.common.concurrent.FileLockGuard;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.executor.support.arm.BoundaryGuard;
import com.lucky.agent.executor.support.arm.FileExecutor;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行臂硬边界 + 文件执行测试（realpath 越界拦截 / 读写 / 软删除）。
 */
class ExecutorBoundaryTest {

    @Test
    void testBoundaryGuardRejectsTraversal() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        BoundaryGuard guard = new BoundaryGuard();
        WorkspaceConfig config = fakeConfig(root);

        assertThrows(AgentException.class, () -> guard.guard(config, "wid", "../escape.txt"));
        assertThrows(AgentException.class, () -> guard.guard(config, "wid", "C:/outside/file.txt"));
    }

    @Test
    void testBoundaryGuardAllowsWithin() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        BoundaryGuard guard = new BoundaryGuard();
        WorkspaceConfig config = fakeConfig(root);

        Path resolved = guard.guard(config, "wid", "src/main/App.java");
        assertTrue(resolved.startsWith(root.toAbsolutePath()));
    }

    @Test
    void testFileExecutorWriteReadDelete() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        WorkspaceDirs dirs = dirsAt(root.getParent().resolve("framework"));
        FileLockGuard lock = new FileLockGuard(dirs.tmpDir());
        FileExecutor executor = new FileExecutor(lock, dirs);

        // 写入
        ExecResult write = executor.execute(
                new FileOp(FileOp.OpType.WRITE).workspaceId("wid").path("a/b.txt").content("hello"),
                root.resolve("a/b.txt"));
        assertTrue(write.ok());

        // 读取
        ExecResult read = executor.execute(
                new FileOp(FileOp.OpType.READ).workspaceId("wid").path("a/b.txt"),
                root.resolve("a/b.txt"));
        assertTrue(read.ok());
        assertEquals("hello", read.content());

        // 软删除 → 进回收站
        ExecResult del = executor.execute(
                new FileOp(FileOp.OpType.DELETE).workspaceId("wid").path("a/b.txt"),
                root.resolve("a/b.txt"));
        assertTrue(del.ok());
        assertFalse(Files.exists(root.resolve("a/b.txt")));
        assertTrue(Files.exists(dirs.trashDir().resolve("wid")));
    }

    private static WorkspaceConfig fakeConfig(Path root) {
        return new WorkspaceConfig() {
            @Override
            public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
                return Optional.of(PermissionLevel.MODIFY);
            }

            @Override
            public Optional<String> physicalPathOf(String workspaceId) {
                return Optional.of(root.toAbsolutePath().toString());
            }

            @Override
            public Optional<Workspace> getWorkspace(String workspaceId) {
                return Optional.of(Workspace.of(workspaceId, "t", root.toAbsolutePath().toString(),
                        PermissionLevel.MODIFY, Instant.now()));
            }

            @Override
            public List<Workspace> listWorkspaces() {
                return List.of();
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
