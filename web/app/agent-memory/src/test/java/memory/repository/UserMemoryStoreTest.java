package memory.repository;

import com.lucky.agent.common.concurrent.ReadWriteLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.repository.UserMemoryStore;
import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import com.lucky.agent.memory.support.pipeline.Cleaner;
import com.lucky.agent.memory.support.pipeline.FactExtractor;
import com.lucky.agent.memory.support.pipeline.NoiseFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户轨记忆持久化测试：追加 → JSONL 落盘 → 重启（新实例）恢复（R7）。
 */
class UserMemoryStoreTest {

    private WorkspaceMemoryPaths paths() {
        // 测试不注入 WorkspaceConfig：目录命名回退旧 sanitize 规则，行为与旧版一致
        return new WorkspaceMemoryPaths(null);
    }

    @Test
    void testAppendPersistsAndReloadsAcrossInstances() throws Exception {
        Path root = Files.createTempDirectory("lucky-mem-test");
        WorkspaceDirs dirs = dirsAt(root);
        ReadWriteLockGuard lock = new ReadWriteLockGuard("test");
        NoiseFilter noiseFilter = new NoiseFilter();
        FactExtractor factExtractor = new FactExtractor();

        UserMemoryStore store = new UserMemoryStore(dirs, lock, noiseFilter, factExtractor, new Cleaner(), paths());
        store.appendUser("u1", "lucky agent 使用 maven 多模块结构并加密存储 api key", 0.8, "user");

        // 模拟进程重启：新建实例从文件恢复
        UserMemoryStore reloaded = new UserMemoryStore(dirs, lock, noiseFilter, factExtractor, new Cleaner(), paths());
        List<MemoryEntry> entries = reloaded.loadAll("u1");

        assertEquals(1, entries.size());
        assertEquals("u1", entries.get(0).userId());
        assertEquals(MemoryEntry.MemoryTrack.USER, entries.get(0).track());
        assertEquals("user", entries.get(0).source());
    }

    @Test
    void testNoiseFilteredNotPersisted() throws Exception {
        Path root = Files.createTempDirectory("lucky-mem-noise");
        WorkspaceDirs dirs = dirsAt(root);
        ReadWriteLockGuard lock = new ReadWriteLockGuard("test");
        UserMemoryStore store = new UserMemoryStore(dirs, lock, new NoiseFilter(), new FactExtractor(), new Cleaner(), paths());

        store.appendUser("u1", "ok", 0.8, "user"); // 过短，噪声过滤
        assertEquals(0, store.loadAll("u1").size());
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
