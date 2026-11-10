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

    @Test
    void testRenameTargetMustStayInsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        Path outside = Files.createTempDirectory("lucky-outside");
        Files.writeString(root.resolve("a.txt"), "x");
        BoundaryGuard guard = new BoundaryGuard();
        WorkspaceConfig config = fakeConfig(root);
        Path source = root.resolve("a.txt").toAbsolutePath();

        // 1) 绝对路径：Path.resolve 会直接采纳，等于把文件写到工作空间外（台账 #1）
        assertThrows(AgentException.class, () -> guard.guardRenameTarget(
                config, "wid", source, outside.resolve("a.txt").toString()));

        // 2) 相对逃逸
        assertThrows(AgentException.class, () -> guard.guardRenameTarget(
                config, "wid", source, "../../escape.txt"));

        // 3) 正常同级 / 子目录改名仍然允许
        Path sibling = guard.guardRenameTarget(config, "wid", source, "b.txt");
        assertEquals(root.toAbsolutePath().normalize().resolve("b.txt"), sibling);
        Path nested = guard.guardRenameTarget(config, "wid", source, "sub/c.txt");
        assertTrue(nested.startsWith(root.toAbsolutePath().normalize()));
    }

    @Test
    void testFileExecutorRenameRejectsAbsoluteTarget() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        Path outside = Files.createTempDirectory("lucky-outside");
        Files.writeString(root.resolve("a.txt"), "x");
        WorkspaceDirs dirs = dirsAt(root.getParent().resolve("framework"));
        FileExecutor executor = new FileExecutor(new FileLockGuard(dirs.tmpDir()), dirs);

        // 直接调用 FileExecutor（绕过 LocalTransport）时也要挡住绝对路径目标
        ExecResult denied = executor.execute(
                new FileOp(FileOp.OpType.RENAME).workspaceId("wid").path("a.txt")
                        .args(java.util.Map.of("to", outside.resolve("a.txt").toString())),
                root.resolve("a.txt"));
        assertFalse(denied.ok());
        assertTrue(Files.exists(root.resolve("a.txt")), "源文件必须原地保留");
        assertFalse(Files.exists(outside.resolve("a.txt")), "不得写到工作空间外");

        // 合法改名仍然成功
        ExecResult ok = executor.execute(
                new FileOp(FileOp.OpType.RENAME).workspaceId("wid").path("a.txt")
                        .args(java.util.Map.of("to", "b.txt")),
                root.resolve("a.txt"));
        assertTrue(ok.ok());
        assertTrue(Files.exists(root.resolve("b.txt")));
    }

    @Test
    void testTrashEntryMustNotEscapeTimestampDir() throws Exception {
        Path root = Files.createTempDirectory("lucky-ws");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("b.txt"), "x");
        // 注意：createTempDirectory 的 parent 是系统临时目录，若直接把 framework 放那里会与
        // 其他用例共用同一个回收站（本文件另一用例也写 wid），断言数目会被串味。这里用唯一目录。
        WorkspaceDirs dirs = dirsAt(root.getParent().resolve("framework-trash-" + System.nanoTime()));
        FileExecutor executor = new FileExecutor(new FileLockGuard(dirs.tmpDir()), dirs);

        // 台账 #10：path 含 .. 时旧实现直接拼进回收站目标名，resolve 会逃出本次时间戳目录
        ExecResult del = executor.execute(
                new FileOp(FileOp.OpType.DELETE).workspaceId("wid").path("a/../b.txt"),
                root.resolve("b.txt"));
        assertTrue(del.ok());

        Path wsTrash = dirs.trashDir().resolve("wid");
        try (var stream = Files.list(wsTrash)) {
            List<Path> entries = stream.toList();
            assertEquals(1, entries.size(), "应只产生一条回收站记录");
            assertFalse(entries.get(0).getFileName().toString().contains(".."),
                    "回收站条目名不得保留 .. 语义");
        }
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
