package memory.util;

import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作空间记忆目录命名测试：
 * <ol>
 *   <li>目录名 = 可读前缀 + 身份摘要，跨调用稳定；</li>
 *   <li>转义塌缩的路径（{@code a\b} / {@code a_b} / {@code a b}）必须落到<b>不同</b>目录；</li>
 *   <li>历史命名（全路径转义 / sanitize）目录在首次访问时自动迁移。</li>
 * </ol>
 */
class WorkspaceMemoryPathsTest {

    @TempDir
    Path tempDir;

    private WorkspaceConfig configOf(Map<String, String> idToPath) {
        WorkspaceConfig config = mock(WorkspaceConfig.class);
        idToPath.forEach((id, path) -> when(config.physicalPathOf(id)).thenReturn(Optional.of(path)));
        return config;
    }

    private WorkspaceConfig configWith(String workspaceId, String path) {
        Map<String, String> single = new HashMap<>();
        single.put(workspaceId, path);
        return configOf(single);
    }

    @Test
    void testDirName_ReadablePrefixPlusDigest() {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(
                configWith("ws-id", "C:\\Users\\LENOVO\\my-proj"));

        String name = paths.dirName("ws-id");

        assertTrue(name.matches("C_Users_LENOVO_my-proj-[0-9a-f]{8}"),
                "目录名应为「可读前缀-摘要」，实际：" + name);
    }

    @Test
    void testDirName_IsStableAcrossCalls() {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configWith("ws-id", "C:\\Users\\LENOVO\\my-proj"));

        assertEquals(paths.dirName("ws-id"), paths.dirName("ws-id"), "同一工作空间必须每次算出同一目录");
    }

    @Test
    void testDirName_EscapedCollisionsGetDistinctDirs() {
        // 转义把 \ : 空格 _ 全部塌缩成同一种字符，这三个路径的 escape 结果完全一样
        Map<String, String> raw = Map.of(
                "ws-slash", "C:\\a\\b",
                "ws-underscore", "C:\\a_b",
                "ws-space", "C:\\a b");
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configOf(raw));

        assertEquals(WorkspaceMemoryPaths.escape("C:\\a\\b"), WorkspaceMemoryPaths.escape("C:\\a_b"),
                "前置条件：转义本身确实塌缩（否则本用例失去意义）");
        assertEquals(WorkspaceMemoryPaths.escape("C:\\a\\b"), WorkspaceMemoryPaths.escape("C:\\a b"),
                "前置条件：空格同样塌缩");

        String slash = paths.dirName("ws-slash");
        String underscore = paths.dirName("ws-underscore");
        String space = paths.dirName("ws-space");
        assertNotEquals(slash, underscore, "不同物理路径不得共用记忆目录");
        assertNotEquals(slash, space, "不同物理路径不得共用记忆目录");
        assertNotEquals(underscore, space, "不同物理路径不得共用记忆目录");
    }

    @Test
    void testDirName_IdFallbackCollisionsGetDistinctDirs() {
        // 物理路径不可得时按 workspaceId 命名：sanitize 同样会把 : 和 / 塌缩成 _
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);

        assertNotEquals(paths.dirName("ws:a"), paths.dirName("ws/a"), "ID 兜底命名同样不得塌缩撞车");
        assertTrue(paths.dirName("ws:a").startsWith("ws_a-"), "兜底命名保留 sanitize 前缀：" + paths.dirName("ws:a"));
    }

    @Test
    void testDirName_BlankIdFallsBackToDefault() {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);

        assertEquals(WorkspaceMemoryPaths.DEFAULT_DIR, paths.dirName(null));
        assertEquals(WorkspaceMemoryPaths.DEFAULT_DIR, paths.dirName("  "));
    }

    @Test
    void testDirOf_MigratesSanitizedLegacyDirectory() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configWith("ws-legacy", "D:\\work\\proj"));
        // 最初版本的目录名：sanitize(workspaceId)
        Path legacy = tempDir.resolve("ws-legacy");
        Files.createDirectories(legacy.resolve("sessions"));
        Files.writeString(legacy.resolve("memory.md"), "旧记忆内容");

        Path fresh = paths.dirOf(tempDir, "ws-legacy");

        assertEquals(tempDir.resolve(paths.dirName("ws-legacy")), fresh, "新目录名应为「前缀-摘要」");
        assertFalse(Files.exists(legacy), "旧目录应被迁移（rename）走");
        assertTrue(Files.exists(fresh.resolve("memory.md")), "迁移后数据应在新目录");
        assertTrue(Files.exists(fresh.resolve("sessions")), "sessions 子目录应一并迁移");
    }

    @Test
    void testDirOf_MigratesEscapedLegacyDirectory() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configWith("ws-escaped", "D:\\work\\proj"));
        // 上一版目录名：escape(物理路径)，不带摘要
        Path legacy = tempDir.resolve(WorkspaceMemoryPaths.escape("D:\\work\\proj"));
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("memory.md"), "上一版记忆内容");

        Path fresh = paths.dirOf(tempDir, "ws-escaped");

        assertNotEquals(legacy, fresh, "带摘要的新目录必然与旧转义目录不同");
        assertFalse(Files.exists(legacy), "上一版转义目录也应被迁移");
        assertEquals("上一版记忆内容", Files.readString(fresh.resolve("memory.md")), "存量记忆不得丢失");
    }

    @Test
    void testSuffixedDirOf_JsonlWorkspaceSuffix() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);
        Path legacy = tempDir.resolve("ws-1_ws");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("ws-1.jsonl"), "{}");

        Path jsonlDir = paths.suffixedDirOf(tempDir, "ws-1", "_ws");

        assertEquals(tempDir.resolve(paths.dirName("ws-1") + "_ws"), jsonlDir, "JSONL 轨目录应带 _ws 后缀");
        assertFalse(Files.exists(legacy), "带后缀的旧目录同样要迁移");
        assertTrue(Files.exists(jsonlDir.resolve("ws-1.jsonl")), "迁移后 JSONL 文件应在新目录");
    }

    @Test
    void testSuffixedDirOf_DoesNotOverwriteExistingFreshDir() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);
        Path fresh = tempDir.resolve(paths.dirName("ws-1"));
        Files.createDirectories(fresh);
        Files.writeString(fresh.resolve("memory.md"), "新记忆");
        Path legacy = tempDir.resolve("ws-1");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("memory.md"), "旧记忆");

        Path resolved = paths.dirOf(tempDir, "ws-1");

        assertEquals(fresh, resolved);
        assertEquals("新记忆", Files.readString(resolved.resolve("memory.md")), "新目录已存在时不得被旧目录覆盖");
        assertTrue(Files.exists(legacy), "新目录已存在时不做迁移（旧目录保持原样）");
    }
}
