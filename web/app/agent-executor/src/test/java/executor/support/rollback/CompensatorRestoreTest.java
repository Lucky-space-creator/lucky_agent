package executor.support.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.api.dto.Snapshot;
import com.lucky.agent.executor.support.rollback.CheckpointSnapshoter;
import com.lucky.agent.executor.support.rollback.Compensator;
import com.lucky.agent.executor.support.rollback.SnapshotIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回滚语义（台账 #12）。
 *
 * <p>修复前只有一个动作：把快照目录里的文件无条件覆盖回工作区。三个后果：
 * 新建文件回滚后残留、RENAME 目标残留、快照后用户改动被静默覆盖。
 * 现在按 {@code __manifest__.json} 精确还原：{@code existed=false} 删除，
 * {@code existed=true} 先比对再覆盖，被改动的先备份。</p>
 */
class CompensatorRestoreTest {

    @TempDir
    Path tmp;

    private Path workspace;
    private WorkspaceDirs dirs;
    private CheckpointSnapshoter snapshoter;
    private Compensator compensator;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(tmp.resolve("ws").resolve("w1"));
        dirs = dirsAt(tmp.resolve("framework"));
        ObjectMapper mapper = new ObjectMapper();
        // 索引走 mock：Snapshot.createdAt 是 Instant，而本模块 classpath 上没有
        // jackson-datatype-jsr310（生产环境由 web 层注入带 JavaTimeModule 的 mapper）。
        // 本测试只关心快照目录的还原语义，索引是否落盘不影响断言。
        SnapshotIndex index = mock(SnapshotIndex.class);
        when(index.read(any(), anyString())).thenReturn(new ArrayList<>());
        snapshoter = new CheckpointSnapshoter(dirs, index, mapper);
        compensator = new Compensator(dirs, mapper);
    }

    @Test
    @DisplayName("操作新建的文件，回滚后必须消失")
    void newFileIsRemovedOnRestore() throws Exception {
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("new.txt"));
        assertThat(cp.createdPaths()).containsExactly("new.txt");

        write("new.txt", "created by agent");
        assertThat(exists("new.txt")).isTrue();

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(exists("new.txt")).as("新建产物必须被回滚删除").isFalse();
    }

    @Test
    @DisplayName("已存在文件被改写，回滚后内容还原")
    void existingFileIsRestored() throws Exception {
        write("a.txt", "orig");
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("a.txt"));
        assertThat(cp.createdPaths()).isEmpty();

        write("a.txt", "changed by agent");

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(read("a.txt")).isEqualTo("orig");
    }

    @Test
    @DisplayName("RENAME 的目标文件也要回滚掉（否则一次重命名变成两个文件）")
    void renameTargetIsRemovedOnRestore() throws Exception {
        write("a.txt", "body");
        // LocalTransport 对 RENAME 会把源和目标一起交给快照器（台账 #12）
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("a.txt", "b.txt"));
        assertThat(cp.createdPaths()).containsExactly("b.txt");

        Files.move(workspace.resolve("a.txt"), workspace.resolve("b.txt"));

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(read("a.txt")).isEqualTo("body");
        assertThat(exists("b.txt")).as("重命名目标必须被回滚删除").isFalse();
    }

    @Test
    @DisplayName("快照后被用户改过的内容，覆盖前先备份到 __pre-restore__")
    void userModificationAfterSnapshotIsBackedUp() throws Exception {
        write("a.txt", "v1");
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("a.txt"));

        write("a.txt", "v3-user-edit");

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(read("a.txt")).isEqualTo("v1");
        Path backup = dirs.rollbackDir().resolve("w1").resolve(cp.checkpointId())
                .resolve(Compensator.PRE_RESTORE_DIR).resolve("a.txt");
        assertThat(Files.isRegularFile(backup)).as("用户改动必须留副本").isTrue();
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo("v3-user-edit");
    }

    @Test
    @DisplayName("操作新建的非空目录不代为删除（快照后已写入内容）")
    void nonEmptyCreatedDirIsKept() throws Exception {
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("d"));
        assertThat(cp.createdPaths()).containsExactly("d");

        Files.createDirectories(workspace.resolve("d"));
        write("d/keep.txt", "user data");

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(exists("d/keep.txt")).as("非空目录不应被回滚删掉用户数据").isTrue();
    }

    @Test
    @DisplayName("清单文件不会被当成工作区文件还原出去")
    void manifestIsNotRestoredIntoWorkspace() throws Exception {
        write("a.txt", "x");
        Snapshot cp = snapshoter.checkpoint("w1", workspace, List.of("a.txt"));

        assertThat(compensator.restore("w1", workspace, cp.checkpointId())).isTrue();

        assertThat(exists(CheckpointSnapshoter.MANIFEST_NAME)).isFalse();
        assertThat(exists(Compensator.PRE_RESTORE_DIR)).isFalse();
    }

    @Test
    @DisplayName("旧快照（无 manifest）仍按全量覆盖还原，历史检查点不失效")
    void legacySnapshotWithoutManifestStillRestores() throws Exception {
        Path legacy = dirs.rollbackDir().resolve("w1").resolve("legacy-cp");
        Files.createDirectories(legacy.resolve("sub"));
        Files.writeString(legacy.resolve("sub").resolve("old.txt"), "legacy", StandardCharsets.UTF_8);

        assertThat(compensator.restore("w1", workspace, "legacy-cp")).isTrue();

        assertThat(read("sub/old.txt")).isEqualTo("legacy");
    }

    // ---------- 辅助 ----------

    private void write(String rel, String content) throws Exception {
        Path p = workspace.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String read(String rel) throws Exception {
        return Files.readString(workspace.resolve(rel), StandardCharsets.UTF_8);
    }

    private boolean exists(String rel) {
        return Files.exists(workspace.resolve(rel));
    }

    /** WorkspaceDirs 的根是私有 String 字段 + @PostConstruct 解析，测试按注释约定反射注入。 */
    private static WorkspaceDirs dirsAt(Path root) throws Exception {
        WorkspaceDirs d = new WorkspaceDirs();
        setField(d, "frameworkRoot", root.toString());
        setField(d, "workspaceRoot", root.toString());
        d.init();
        return d;
    }

    private static void setField(WorkspaceDirs d, String name, String value) throws Exception {
        var f = WorkspaceDirs.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(d, value);
    }
}
