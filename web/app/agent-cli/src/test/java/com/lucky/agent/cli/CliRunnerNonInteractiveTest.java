package com.lucky.agent.cli;

import com.lucky.agent.cli.channel.CliChannel;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 非交互（非 TTY / {@code --plain}）下的启动语义回归。
 *
 * <p><b>行为演进</b>：早期实现在非交互 + 工作空间多候选时仍把非空 {@code LineEditor} 传给
 * {@code pickWorkspace}，于是 {@code readLine} 在「已连接但无数据」的管道上永久阻塞（修复 #2）；
 * 随后改为「非交互不询问 + 直接 USAGE(2)」—— 但这又矫枉过正：IDE 运行控制台等高频场景
 * 连会话都进不去，工作空间缺乏默认落点。</p>
 *
 * <p><b>现行契约</b>（本次改动）：</p>
 * <ol>
 *   <li>非交互下<b>不主动读 stdin 询问</b>工作空间，避免把管道数据行当成序号吃掉；</li>
 *   <li>多候选且无法询问时<b>回落到内置默认工作空间并继续</b>（「没选也必须有一个默认落点」）；</li>
 *   <li>确实没有内置默认空间时才报 {@code USAGE(2)}；</li>
 *   <li>非 TTY 也允许进入纯文本会话（逐行读 stdin，EOF / {@code /exit} 即退出）。</li>
 * </ol>
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

    /** 内置默认工作空间（{@code builtin=true}，物理路径位于框架根内）。 */
    private static Workspace builtinWs(String id, String name, String path) {
        Workspace w = ws(id, name, path);
        w.builtin(true);
        return w;
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
                new CliChannel(null, null), // channel：非 TTY 进会话时会 attachRenderer，不能为 null
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

    /** 把 stdin 替换为「已连接但无数据」的管道：read() 会永久阻塞，用于证明实现不会去读 stdin。 */
    private static InputStream blockOnReadStdin() throws Exception {
        java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
        return new java.io.PipedInputStream(pos); // 连接但不写数据 → read 阻塞
    }

    /** 把 stdin 替换为给定脚本文本（自动补换行），驱动纯文本会话。 */
    private static InputStream scriptedStdin(String script) {
        return new ByteArrayInputStream((script + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("非交互 + 多工作空间（含内置）→ 回落内置默认并进入纯文本会话，不阻塞")
    void ambiguousFallsBackToBuiltinAndEntersRepl() throws Exception {
        InputStream originalIn = System.in;
        System.setIn(scriptedStdin("/exit"));
        try {
            CliOptions o = new CliOptions();
            o.plain = true; // 强制纯文本 → 非交互
            int code = runner(workspacesReturning(List.of(
                    builtinWs("b0", "内置默认", "/fw/workspace"),
                    ws("a", "甲", "/data/a"),
                    ws("b", "乙", "/data/b")))).run(o);
            assertEquals(CliExitCode.SUCCESS, code,
                    "多候选且非交互时应回落到内置默认工作空间继续，而不是直接退出");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("非交互 + 多工作空间 + 无内置默认 → USAGE(2)，且不读 stdin（不阻塞）")
    void ambiguousWithoutBuiltinReturnsUsage() throws Exception {
        InputStream originalIn = System.in;
        System.setIn(blockOnReadStdin()); // 若实现去问用户就会永久阻塞 → Timeout 失败
        try {
            CliOptions o = new CliOptions();
            o.plain = true;
            int code = runner(workspacesReturning(List.of(
                    ws("a", "甲", "/data/a"), ws("b", "乙", "/data/b")))).run(o);
            assertEquals(CliExitCode.USAGE, code,
                    "没有任何默认落点时才报用法错误；且绝不能阻塞在 readLine");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("非交互 + 单工作空间 + 无 -p → 进入纯文本会话（不再是 USAGE）")
    void singleWorkspaceNonInteractiveEntersPlainRepl() throws Exception {
        InputStream originalIn = System.in;
        System.setIn(scriptedStdin("/exit"));
        try {
            CliOptions o = new CliOptions();
            o.plain = true;
            int code = runner(workspacesReturning(List.of(ws("a", "甲", "/data/a")))).run(o);
            assertEquals(CliExitCode.SUCCESS, code,
                    "非交互环境也应能进入纯文本会话（读 stdin 逐行处理，EOF / /exit 退出）");
        } finally {
            System.setIn(originalIn);
        }
    }
}
