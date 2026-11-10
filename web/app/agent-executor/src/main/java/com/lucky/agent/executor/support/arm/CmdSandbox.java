package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行沙箱（默认关闭，开启需用户显式授权）。
 *
 * <p>MVP 以受限子进程执行（工作目录 = 工作空间根，带超时）；OS 级沙箱在 Phase 2 补齐。</p>
 *
 * <p><b>为什么必须经 shell 执行（缺陷 D16 的修复点）</b>：此前实现把命令行按空白切分后直接
 * 交给 {@link ProcessBuilder}，于是 {@code echo} / {@code dir} / {@code type} / {@code cd} 这类
 * <b>shell 内建命令</b>全部报 {@code CreateProcess error=2}（它们不是可执行文件），
 * 管道 {@code |}、重定向 {@code >}、串联 {@code &&} 等 shell 语义也一概不成立。
 * 而「执行一条命令」的用户预期就是 shell 语义 —— 现在统一走
 * Windows {@code cmd /c}、POSIX {@code /bin/sh -c}。
 * 安全边界不因走 shell 而放松：命令在进入本类之前已通过
 * 权限裁决（deny &gt; ask &gt; allow）与 OS 沙箱红线检测（见 {@code LocalTransport#execute}）。</p>
 */
public class CmdSandbox {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 输出排空线程的收尾等待上限：进程已退出，管道随即 EOF，正常是毫秒级。 */
    private static final long DRAIN_JOIN_MS = 1000L;

    /** 失败时 error 里回显输出的上限：够看清原因，又不至于把整份构建日志塞进错误字段。 */
    private static final int ERROR_OUTPUT_LIMIT = 800;

    private final boolean enabled;
    private final long timeoutSec;

    public CmdSandbox(boolean enabled, long timeoutSec) {
        this.enabled = enabled;
        this.timeoutSec = timeoutSec;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 在工作空间根内执行命令。
     *
     * @param op            文件操作（EXEC）
     * @param workspaceRoot 工作空间根
     * @return 执行结果（仅元数据）
     */
    public ExecResult run(FileOp op, Path workspaceRoot) {
        if (!enabled) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行沙箱未启用，需用户显式授权");
        }
        String command = op.args() == null ? null : (String) op.args().get("command");
        if (command == null || command.isBlank()) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "缺少命令参数 command");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(shellCommand(command));
            pb.directory(workspaceRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 必须「并发排空输出 + 带超时等待」，不能先 readAllBytes 再 waitFor：
            // 后者在命令长期不结束时，读操作会一直阻塞在管道上，waitFor 的超时**永远不会被判定**，
            // 于是 timeoutSec 形同虚设（同时子进程写满管道缓冲区后也会卡住 —— 双向死锁）。
            // 这里把原始字节收进内存，最后一次性解码，避免按块解码把多字节字符截断。
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread drainer = Thread.ofVirtual().name("cmd-drain").start(() -> drain(process, sink));

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                drainer.join(DRAIN_JOIN_MS);
                return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(),
                        "命令执行超时（" + timeoutSec + "s），已强制终止");
            }
            drainer.join(DRAIN_JOIN_MS);
            int exit = process.exitValue();
            String output = decode(sink.toByteArray());
            // 非零退出 = 命令失败（台账 #5）。此前无条件返回 success，退出码只进 summary，
            // 于是 CommandVerifier 的 `!result.ok()` 永远为 false —— 校验命令失败也被判「通过」，
            // 未验证的改动被当作已验证推进。现在 ok 与 exitCode 语义一致，输出仍原样带回，
            // 让调用方/模型能看到 stderr。
            if (exit != 0) {
                String error = "命令退出码 " + exit + (output.isBlank() ? "" : "：" + tail(output));
                return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), error)
                        .exitCode(exit)
                        .content(output)
                        .summary("exit=" + exit);
            }
            return ExecResult.success(FileOp.OpType.EXEC, op.requestId())
                    .exitCode(exit)
                    .summary("exit=" + exit).content(output);
        } catch (IOException e) {
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令启动失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure(FileOp.OpType.EXEC, op.requestId(), "命令执行被中断");
        }
    }

    /** shell 调用形式：Windows 用 {@code cmd /c}，其余用 {@code /bin/sh -c}，整条命令原样交给 shell。 */
    private static List<String> shellCommand(String command) {
        if (WINDOWS) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    /**
     * 超时后终止<b>整棵进程树</b>。
     *
     * <p>只 destroy 直接子进程是不够的：本类是通过 shell 间接启动命令的
     * （{@code cmd /c} / {@code sh -c}），被强杀的只是 shell，真正干活的孙进程会变成孤儿继续跑 ——
     * 实测表现为「命令已报超时，但 {@code ping} 仍在运行，并占住工作目录使其无法删除」。
     * 先取一次后代快照再逐个终止：根进程被杀后 {@code descendants()} 可能已为空。</p>
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private static void drain(Process process, ByteArrayOutputStream sink) {        try (InputStream in = process.getInputStream()) {
            in.transferTo(sink);
        } catch (IOException ignored) {
            // 进程被强制终止时管道会异常关闭，属预期情况，不改变结果语义
        }
    }

    /** 截取输出尾部用于 error 回显（编译/测试失败的关键信息通常在末尾）。 */
    private static String tail(String output) {
        String trimmed = output.strip();
        if (trimmed.length() <= ERROR_OUTPUT_LIMIT) {
            return trimmed;
        }
        return "…" + trimmed.substring(trimmed.length() - ERROR_OUTPUT_LIMIT);
    }

    /**
     * 解码命令输出。
     *
     * <p>命令的真实输出编码我们无从预知：现代工具多输出 UTF-8，而 Windows 内建命令跟随控制台
     * 代码页（中文环境通常是 GBK）。此前用平台默认字符集无条件解码，在 {@code -Dfile.encoding=UTF-8}
     * 启动时会把 GBK 输出解成乱码。这里改为：<b>先严格试 UTF-8</b>（失败即能识别出「不是 UTF-8」，
     * 不会静默产出替换字符），失败再退回 JVM 探测到的控制台编码，最后才退回平台默认。</p>
     */
    private static String decode(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strictUtf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            // 非 UTF-8：优先用 JVM 探测到的控制台编码（JDK 19+ 提供 stdout.encoding），
            // 它正是 cmd 内建命令输出所用的代码页
            String console = System.getProperty("stdout.encoding");
            if (console != null && !console.isBlank()) {
                try {
                    return new String(bytes, Charset.forName(console));
                } catch (Exception ignored) {
                    // 编码名不可用则继续退回
                }
            }
            return new String(bytes, Charset.defaultCharset());
        }
    }
}