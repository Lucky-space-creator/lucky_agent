package memory.repository;

import com.lucky.agent.common.concurrent.ReadWriteLockGuard;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.repository.UserMemoryStore;
import com.lucky.agent.memory.support.pipeline.Cleaner;
import com.lucky.agent.memory.support.pipeline.FactExtractor;
import com.lucky.agent.memory.support.pipeline.NoiseFilter;
import com.lucky.agent.memory.util.WorkspaceMemoryPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「做梦清理」的衰减必须是幂等的（台账 #24）。
 *
 * <p>修复前 {@code sweepFile} 把 {@code decayed(confidence)} 的结果<b>写回文件</b>，
 * 下次 sweep 又在衰减值上再乘一遍：一次改写就让同一条记忆的衰减指数翻倍，
 * 10 天龄的条目被按 21 天档扣分，提前跌破阈值被物理删除。叠加读取侧
 * {@code MemoryRetrieverImpl} 还会再 apply 一次 {@code DecayManager}，等于同一条记忆被扣两遍。</p>
 *
 * <p>修复后衰减值只用于「淘汰判定」，原始 confidence 永不改写。下面的断言是：
 * 连跑三次 sweep，条目数与置信度都不变 —— 旧实现第三次就会把条目全删光。</p>
 */
class UserMemoryStoreSweepTest {

    private static final String USER = "u-sweep";

    @Test
    @DisplayName("#24 连续 sweep 不得滚雪球式衰减（三次后条目与置信度都不变）")
    void decayDoesNotCompoundAcrossSweeps() throws Exception {
        Path root = Files.createTempDirectory("lucky-mem-sweep-");
        UserMemoryStore store = storeAt(root);
        String tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).toString();

        store.append(entry(tenDaysAgo, "项目使用 Maven 多模块结构统一管理依赖版本"));
        store.append(entry(tenDaysAgo, "工作流引擎通过 DagCompiler 做拓扑排序与环检测"));
        store.append(entry(tenDaysAgo, "执行臂对所有写操作先建快照再落盘以便回滚"));

        assertThat(store.loadAll(USER)).hasSize(3);

        // 单项目容量上限 2 → 必然触发一次「改写」，这正是旧实现开始滚雪球的时机
        store.sweep(0.9, 0.1, 2);
        List<MemoryEntry> afterFirst = store.loadAll(USER);
        assertThat(afterFirst)
                .as("容量上限生效")
                .hasSize(2);
        assertThat(afterFirst)
                .as("衰减值不得回写：原始置信度必须原样保留")
                .allMatch(e -> e.confidence() == 1.0);

        store.sweep(0.9, 0.1, 2);
        store.sweep(0.9, 0.1, 2);
        List<MemoryEntry> afterThird = store.loadAll(USER);

        assertThat(afterThird)
                .as("旧实现在第三次会把 1.0×0.9^10×0.9^10×0.9^10 压到阈值以下并全部删除")
                .hasSize(2);
        assertThat(afterThird).allMatch(e -> e.confidence() == 1.0);
    }

    @Test
    @DisplayName("#24 sweep 后缓存必须失效，否则被删的记忆仍会被检索到")
    void sweepInvalidatesStaleCache() throws Exception {
        Path root = Files.createTempDirectory("lucky-mem-cache-");
        UserMemoryStore store = storeAt(root);
        String tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).toString();

        store.append(entry(tenDaysAgo, "缓存里先装三条记忆，随后由清理逻辑裁到一条"));
        store.append(entry(tenDaysAgo, "第二条记忆用于触发容量上限裁剪行为"));
        store.append(entry(tenDaysAgo, "第三条记忆用于触发容量上限裁剪行为"));
        assertThat(store.loadAll(USER)).hasSize(3);   // 填充缓存

        store.sweep(0.9, 0.1, 1);

        assertThat(store.loadAll(USER))
                .as("缓存不清会让用户看到「已经被遗忘的记忆又冒出来」")
                .hasSize(1);
    }

    @Test
    @DisplayName("#24 容量上限非正数应按「不限」处理，不得清空全部记忆")
    void nonPositiveMaxPerScopeMeansUnlimited() throws Exception {
        Path root = Files.createTempDirectory("lucky-mem-limit-");
        UserMemoryStore store = storeAt(root);
        String tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS).toString();

        store.append(entry(tenDaysAgo, "配置成 0 通常被理解为「不限制容量」而非「一条都不要」"));
        store.append(entry(tenDaysAgo, "旧实现直接 limit(0)，会把整个记忆文件清空"));

        store.sweep(1.0, 0.1, 0);   // decay=1.0：完全不衰减，只测容量语义

        assertThat(store.loadAll(USER)).hasSize(2);
    }

    private static MemoryEntry entry(String ts, String content) {
        return MemoryEntry.user(ts, USER, content, 1.0, "user");
    }

    private static UserMemoryStore storeAt(Path root) throws Exception {
        WorkspaceDirs dirs = new WorkspaceDirs();
        set(dirs, "frameworkRoot", root.toString());
        set(dirs, "workspaceRoot", root.resolve("ws").toString());
        dirs.init();
        return new UserMemoryStore(dirs, new ReadWriteLockGuard("test"),
                new NoiseFilter(), new FactExtractor(), new Cleaner(), new WorkspaceMemoryPaths(null));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
