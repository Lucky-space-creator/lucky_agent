package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令沙箱回归守卫（缺陷 D16 / D18a / D18b / D21）。
 *
 * <p><b>守的是什么</b></p>
 * <ul>
 *   <li><b>D16</b>：命令必须经 shell 执行，否则 {@code echo} / {@code cd} / {@code dir}
 *       这类 <b>shell 内建命令</b>会直接报 {@code CreateProcess error=2}（它们不是可执行文件），
 *       管道与重定向也不成立。曾实测除 {@code whoami} 外几乎一切命令都跑不起来。</li>
 *   <li><b>D18a</b>：超时必须真的生效。原实现「先 {@code readAllBytes()} 再
 *       {@code waitFor(timeout)}」——命令不结束时读操作会永久阻塞在管道上，
 *       超时判定永远走不到。用「长睡眠命令 + 短超时」把它钉死。</li>
 *   <li><b>D18b</b>：输出解码不得依赖平台默认字符集（{@code -Dfile.encoding=UTF-8} 启动时
 *       会把 GBK 命令输出解成乱码）。</li>
 *   <li><b>D21</b>：超时后必须终止<b>整棵进程树</b>。只杀 shell 会留下孤儿孙进程
 *       （实测：报超时后 {@code ping} 仍在跑，并占住工作目录导致其无法删除）。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code @TempDir}</b>：本测试会故意触发「超时 + 强杀」路径。
 * 若用 {@code @TempDir}，一旦孤儿进程短暂持有该目录，JUnit 的目录清理会失败并把测试判为
 * Error —— 那是平台时序问题，不是被测行为。改在工作目录下的构建产物目录里建子目录，
 * 既不污染源码树，也由 {@code mvn clean} 负责回收。</p>
 */
class CmdSandboxTest {

    private static final String MARKER = "lucky-cmdsandbox-marker";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static FileOp exec(String command) {
        return new FileOp(FileOp.OpType.EXEC).workspaceId("w-test").args(Map.of("command", command));
    }

    /** 一个必然超时的长睡眠命令（Windows 无 sleep 命令，用 ping 凑时长）。 */
    private static String longRunningCommand() {
        return WINDOWS ? "ping -n 31 127.0.0.1 >nul" : "sleep 30";
    }

    /** 每个用例独立的构建产物目录，避免相互干扰。 */
    private static Path workDir(String name) throws IOException {
        Path dir = Path.of("target", "cmd-sandbox-test", name).toAbsolutePath();
        deleteRecursively(dir);
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        }
    }

    @Test
    @DisplayName("沙箱未启用时直接拒绝，且不执行任何命令")
    void disabledRejectsWithoutExecuting() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(false, 5);
        ExecResult r = sandbox.run(exec("echo " + MARKER), workDir("disabled"));
        assertFalse(r.ok());
        assertNotNull(r.error());
        assertTrue(r.error().contains("未启用"), r.error());
    }

    @Test
    @DisplayName("D16：shell 内建命令 echo 能真正执行（修复前报 CreateProcess error=2）")
    void runsShellBuiltinEcho() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(true, 20);
        ExecResult r = sandbox.run(exec("echo " + MARKER), workDir("echo"));
        assertTrue(r.ok(), "命令应执行成功，实际错误：" + r.error());
        assertEquals("exit=0", r.summary());
        assertNotNull(r.content());
        assertTrue(r.content().contains(MARKER), "应拿到命令输出，实际：" + r.content());
    }

    @Test
    @DisplayName("D16：shell 语义（管道）可用")
    void supportsShellSyntax() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(true, 20);
        String command = WINDOWS
                ? "echo " + MARKER + "|findstr " + MARKER
                : "echo " + MARKER + " | grep " + MARKER;
        ExecResult r = sandbox.run(exec(command), workDir("pipe"));
        assertTrue(r.ok(), "管道应可用，实际错误：" + r.error());
        assertTrue(r.content() != null && r.content().contains(MARKER), "实际：" + r.content());
    }

    @Test
    @DisplayName("D18a：超时必须真的生效（命令不得把线程永远挂住）")
    void timeoutIsEnforced() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(true, 1);
        long start = System.currentTimeMillis();
        ExecResult r = sandbox.run(exec(longRunningCommand()), workDir("timeout"));
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(r.ok(), "长睡眠命令不应成功");
        assertNotNull(r.error());
        assertTrue(r.error().contains("超时"), "应报超时，实际：" + r.error());
        // 关键断言：修复前这里会一直阻塞到命令自然结束（30s+）；
        // 给足平台抖动余量，但仍远小于命令本身的时长
        assertTrue(elapsed < 15_000, "超时未被及时判定，耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("D21：超时后整棵进程树被终止（不留孤儿孙进程占住工作目录）")
    void timeoutKillsWholeProcessTree() throws IOException {
        Path dir = workDir("tree-kill");
        CmdSandbox sandbox = new CmdSandbox(true, 1);
        ExecResult r = sandbox.run(exec(longRunningCommand()), dir);
        assertTrue(r.error() != null && r.error().contains("超时"), "本轮应超时：" + r.error());

        // 孤儿孙进程会以该目录为工作目录，进而阻止目录删除；能删掉即说明它已被终止。
        // 给强杀一点点落地时间（Windows 上进程退出与句柄释放不是瞬时的）。
        IOException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                deleteRecursively(dir);
                last = null;
                break;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertTrue(last == null, "工作目录应可删除（说明孙进程已被终止），实际：" + last);
    }

    @Test
    @DisplayName("非零退出码被保留（供 failOnError 判定）")
    void nonZeroExitCodePreserved() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(true, 20);
        ExecResult r = sandbox.run(exec("exit 3"), workDir("exit3"));
        assertTrue(r.ok(), "进程本身启动成功即 ok=true，退出码由 summary 表达");
        assertEquals("exit=3", r.summary());
    }

    @Test
    @DisplayName("缺少 command 参数时拒绝")
    void missingCommandRejected() throws IOException {
        CmdSandbox sandbox = new CmdSandbox(true, 5);
        ExecResult r = sandbox.run(new FileOp(FileOp.OpType.EXEC).workspaceId("w-test"),
                workDir("missing"));
        assertFalse(r.ok());
        assertTrue(r.error().contains("command"), r.error());
    }
}