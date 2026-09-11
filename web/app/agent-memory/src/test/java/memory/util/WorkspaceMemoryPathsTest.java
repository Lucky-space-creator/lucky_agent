package memory.util;

import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作空间记忆目录命名测试：全路径转义命名 + 旧目录自动迁移。
 */
class WorkspaceMemoryPathsTest {

    @TempDir
    Path tempDir;

    private WorkspaceConfig configWith(String workspaceId, String path) {
        WorkspaceConfig config = mock(WorkspaceConfig.class);
        when(config.physicalPathOf(workspaceId)).thenReturn(Optional.of(path));
        return config;
    }

    @Test
    void testDirName_UsesEscapedPhysicalPath() {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configWith("ws-id", "C:\\Users\\LENOVO\\my-proj"));
        assertEquals("C_Users_LENOVO_my-proj", paths.dirName("ws-id"));
    }

    @Test
    void testDirName_FallbackToLegacy_WhenNoConfig() {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);
        assertEquals("ws-id", paths.dirName("ws-id"), "无 WorkspaceConfig 时回退旧 sanitize 规则");
    }

    @Test
    void testDirOf_MigratesLegacyDirectory() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(configWith("ws-legacy", "D:\\work\\proj"));
        // 构造旧版目录（sanitize(workspaceId)）与其中数据
        Path legacy = tempDir.resolve("ws-legacy");
        Files.createDirectories(legacy.resolve("sessions"));
        Files.writeString(legacy.resolve("memory.md"), "旧记忆内容");

        Path fresh = paths.dirOf(tempDir, "ws-legacy");

        assertEquals(tempDir.resolve("D_work_proj"), fresh, "新目录名应为全路径转义");
        assertFalse(Files.exists(legacy), "旧目录应被迁移（rename）走");
        assertTrue(Files.exists(fresh.resolve("memory.md")), "迁移后数据应在新目录");
        assertTrue(Files.exists(fresh.resolve("sessions")), "sessions 子目录应一并迁移");
    }

    @Test
    void testSuffixedDirOf_JsonlWorkspaceSuffix() throws Exception {
        WorkspaceMemoryPaths paths = new WorkspaceMemoryPaths(null);
        Path jsonlDir = paths.suffixedDirOf(tempDir, "ws-1", "_ws");
        assertEquals(tempDir.resolve("ws-1_ws"), jsonlDir, "JSONL 轨目录应带 _ws 后缀");
    }
}