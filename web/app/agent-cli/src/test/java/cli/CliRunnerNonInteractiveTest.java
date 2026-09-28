package cli;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.CliRunner;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 非交互（非 TTY / --plain）下的退出语义回归。
 *
 * <p>修复 #2（非 TTY 工作空间歧义挂起）之前：{@code CliRunner} 在非交互环境下仍把非空的
 * {@code LineEditor} 传给 {@code pickWorkspace}，当工作空间存在多候选时会在
 * {@code editor.readLine(...)} 上永久阻塞（管道/无输入场景下 stdin 无数据）。本测试通过把
 * {@code System.in} 替换为「已连接但无数据」的管道，确定性地复现该阻塞；若未修复，
 * {@code readLine} 会卡住、触发 {@link Timeout} 使测试失败。</p>
 */
class CliRunnerNonInteractiveTest {

    private static WorkspaceConfig workspacesReturning(List<Workspace> list) {
        return new WorkspaceConfig() {
            @Override
            public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
                return list.stream().filter(w -> w.workspaceId().equals(workspaceId))
                        .map(Workspace::permissionLevel).findFirst();
            }

            @Override
            public Optional<String> physicalPathOf(String workspaceId) {
                return list.stream().filter(w -> w.workspaceId().equals(workspaceId))
                        .map(Workspace::path).findFirst();
            }

            @Override
            public Optional<Workspace> getWorkspace(String workspaceId) {
                return list.stream().filter(w -> w.workspaceId().equals(workspaceId)).findFirst();
            }

            @Override
            public List<Workspace> listWorkspaces() {
                return list;
            }
        };
    }

    private static Workspace ws(String id, String name, String path) {
        return new Workspace().workspaceId(id).name(name).path(path)
                .permissionLevel(PermissionLevel.MODIFY);
    }

    /** 仅覆盖 {@code frameworkRoot()}：run() 仅用其定位项目配置，返回临时目录即可，避免触碰真实框架根。 */
    private static WorkspaceDirs tempDirs() {
        return new WorkspaceDirs() {
            @Override
            public java.nio.file.Path frameworkRoot() {
                return Paths.get(System.getProperty("java.io.tmpdir"));
            }
        };
    }

    private static CliRunner runner(WorkspaceConfig workspaces) {
        return new CliRunner(
                null, // channel（早退路径不使用）
                null, // conversations
                null, // stateManager
                null, // CliSessionService sessions
                workspaces,
                tempDirs(),
                null, // metrics
                null, // files
                null  // permission
        );
    }

    /** 把 stdin 替换为「已连接但无数据」的管道：read() 会永久阻塞，用于复现非 TTY 下的挂起。 */
    private static InputStream blockOnReadStdin() throws Exception {
        PipedOutputStream pos = new PipedOutputStream();
        return new PipedInputStream(pos); // 连接但不写数据 → read 阻塞
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("非交互 + 多工作空间未指定 -w → USAGE(2)，不阻塞在 readLine")
    void ambiguousWorkspaceNonInteractiveReturnsUsage() throws Exception {
        InputStream original = System.in;
        System.setIn(blockOnReadStdin());
        try {
            CliOptions o = new CliOptions();
            o.plain = true; // 强制纯文本 → 非交互
            int code = runner(workspacesReturning(List.of(
                    ws("a", "甲", "/data/a"), ws("b", "乙", "/data/b")))).run(o);
            assertEquals(CliExitCode.USAGE, code,
                    "非交互下工作空间歧义必须报错退出，而非卡在 readLine");
        } finally {
            System.setIn(original);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("非交互 + 单工作空间 + 无 -p → 无法进入 REPL → USAGE(2)")
    void singleWorkspaceNoReplNonInteractiveReturnsUsage() throws Exception {
        InputStream original = System.in;
        System.setIn(blockOnReadStdin());
        try {
            CliOptions o = new CliOptions();
            o.plain = true;
            int code = runner(workspacesReturning(List.of(ws("a", "甲", "/data/a")))).run(o);
            assertEquals(CliExitCode.USAGE, code,
                    "非交互环境不能进入 REPL，应明确报错退出");
        } finally {
            System.setIn(original);
        }
    }
}
