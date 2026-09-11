package memory.support.md;

import com.lucky.agent.memory.support.md.MemoryIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两级记忆索引（MEMORY.md）单元测试：锚点幂等、索引生成、按锚点读取、上限截断。
 */
class MemoryIndexTest {

    private final MemoryIndex index = new MemoryIndex(200, 25600);

    @TempDir
    Path tempDir;

    @Test
    void testIndex_GeneratesAnchorsAndIndexLines() {
        MemoryIndex.IndexedContent result = index.index("""
                - [project] 使用 Maven 多模块结构
                - [feedback] 不要用 git push --force
                - [user] 用户偏好本地优先""");

        assertEquals(3, result.indexLines().size());
        assertTrue(result.contentWithAnchors().contains("<a name=\"mem-"), "条目应带锚点");
        assertTrue(result.indexLines().get(0).startsWith("- [project]"), "索引行应带分类与描述");
        assertTrue(result.indexLines().get(0).contains("#mem-"), "索引行应指向锚点 id");
    }

    @Test
    void testIndex_Idempotent_SameContentSameAnchor() {
        String content = "- [project] 数据库选用 SQLite\n- [user] 喜欢中文注释";
        MemoryIndex.IndexedContent first = index.index(content);
        MemoryIndex.IndexedContent second = index.index(content);

        assertEquals(first.contentWithAnchors(), second.contentWithAnchors(), "相同内容应生成相同锚点与全文");
        assertEquals(first.indexLines(), second.indexLines(), "相同内容应生成相同索引行");
    }

    @Test
    void testReadEntries_ExtractsByAnchorId() {
        MemoryIndex.IndexedContent indexed = index.index("- [project] 后端用 JDK 17\n- [user] 前端用 Vue 3");
        Path file = tempDir.resolve("memory.md");
        try {
            Files.writeString(file, indexed.contentWithAnchors());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String id = indexed.indexLines().get(0).substring(indexed.indexLines().get(0).indexOf("#") + 1);

        var entries = index.readEntries(file, Set.of(id));
        assertEquals(1, entries.size(), "只应命中指定的锚点条目");
        assertTrue(entries.get(id).contains("JDK 17"), "应返回条目全文");
    }

    @Test
    void testWriteIndex_RespectsMaxLines() {
        // 上限钳制至少 10 行：用 20 行数据对 10 行上限验证截断
        MemoryIndex small = new MemoryIndex(10, 25600);
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("line-" + i);
        }
        small.writeIndex(tempDir, lines);

        String content = small.readIndex(tempDir);
        assertTrue(content.contains("Dream 将修剪"), "超上限应打修剪标记");
        assertTrue(content.split("\n").length < 30, "索引应被截断到上限附近");
    }

    @Test
    void testEscape_WindowsPathToDirName() {
        assertEquals("C_Users_LENOVO_新项目", com.lucky.agent.memory.util.WorkspaceMemoryPaths
                .escape("C:\\Users\\LENOVO\\新项目"));
        assertEquals("default", com.lucky.agent.memory.util.WorkspaceMemoryPaths.escape(""));
    }
}