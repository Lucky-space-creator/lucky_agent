package com.lucky.agent.cli.channel;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * CLI 的唯一输出通道（输出通道唯一化，见 CLI 方案 §1.5 风险 1）。
 *
 * <p><b>为什么必须唯一</b>：JLine 接管终端后进入 raw mode 并自行管理行编辑区，任何绕过它的
 * {@code System.out.println} 都会与行编辑区争夺光标位置，表现为「状态行被冲掉、提示符错位、
 * 残留上一个界面的垃圾字符」。故 {@code repl} / {@code channel} 包内禁止直接使用
 * {@code System.out}，统一经本类写出；本类是唯一持有 {@link Writer} 的地方。</p>
 *
 * <p><b>编码</b>：两条路径都强制 UTF-8（{@link java.io.PrintStream} 分支显式指定字符集），
 * 避免 Windows 默认代码页（GBK/936）把中文写成乱码。</p>
 */
public final class OutputSink implements Closeable {

    /** UTF-8 stdout 单例（见 {@link #utf8Stdout()} 的幂等说明）。 */
    private static volatile PrintStream utf8Out;
    /** UTF-8 stderr 单例。 */
    private static volatile PrintStream utf8Err;

    private final Writer writer;
    private final PrintStream fallbackStream;
    private final Theme theme;

    private OutputSink(Writer writer, PrintStream fallbackStream, Theme theme) {
        this.writer = writer;
        this.fallbackStream = fallbackStream;
        this.theme = theme;
    }

    /** JLine 终端分支：所有输出经 {@code terminal.writer()}。 */
    public static OutputSink of(Writer terminalWriter, Theme theme) {
        return new OutputSink(terminalWriter, null, theme);
    }

    /** 纯文本分支（非 TTY / {@code --plain}）：包装 UTF-8 的 {@link PrintStream}。 */
    public static OutputSink of(PrintStream stream, Theme theme) {
        return new OutputSink(null, stream, theme);
    }

    /**
     * 取得强制 UTF-8 的 stdout，并<b>同时替换 {@code System.out}</b>。幂等：重复调用返回同一实例。
     *
     * <p>为什么要替换全局：若只包我们自己的流，日志（logback ConsoleAppender）仍走旧的
     * {@code System.out}，两个 {@link PrintStream} 各自缓冲同一 {@code FileDescriptor}，
     * 会出现「半行中文被另一路插进来」的交错乱码。统一到一个流后，日志与正常输出共享同一缓冲，
     * 且都按 UTF-8 编码。</p>
     *
     * <p><b>为什么必须幂等</b>：入口在 Spring 启动前先换一次流（让 logback 捕获到 UTF-8 流），
     * headless 分支还会再取一次。若第二次又新建一个 {@link PrintStream} 包同一个
     * {@code FileDescriptor}，就正好制造出上面那段注释想避免的交错 —— 同一入口重复调用必须是
     * 「取回同一个流」，不能是「再包一层」。</p>
     */
    public static PrintStream utf8Stdout() {
        PrintStream cached = utf8Out;
        if (cached != null) {
            return cached;
        }
        synchronized (OutputSink.class) {
            if (utf8Out == null) {
                utf8Out = new PrintStream(
                        new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
                System.setOut(utf8Out);
            }
            return utf8Out;
        }
    }

    /**
     * 强制 UTF-8 的 stderr（幂等，同 {@link #utf8Stdout()}）。
     *
     * <p>headless 文本模式把 <b>进度</b>写 stderr、<b>正文</b>写 stdout，这样
     * {@code lucky -p "..." > answer.txt} 拿到的就是干净的答案，而进度不会污染重定向结果。
     * 与 stdout 同理，必须显式 UTF-8，否则 Windows 下中文进度行会乱码。</p>
     */
    public static PrintStream utf8Stderr() {
        PrintStream cached = utf8Err;
        if (cached != null) {
            return cached;
        }
        synchronized (OutputSink.class) {
            if (utf8Err == null) {
                utf8Err = new PrintStream(
                        new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
                System.setErr(utf8Err);
            }
            return utf8Err;
        }
    }

    public Theme theme() {
        return theme;
    }

    /**
     * 本通道实际使用的字符集（恒为 UTF-8）。
     *
     * <p>存在的理由：{@code /doctor} 需要如实报告输出编码。读 {@code System.getProperty("stdout.encoding")}
     * 是错的 —— 那是 JVM 启动时按系统代码页定下的值（Windows 上常见 GBK），而我们早已把
     * {@code System.out}/{@code System.err} 换成了强制 UTF-8 的流。用启动值会让排障者得出
     * 「输出是 GBK」的错误结论，进而去查一个并不存在的问题。真实值由这里给出。</p>
     */
    public static java.nio.charset.Charset outputCharset() {
        return StandardCharsets.UTF_8;
    }

    public void print(String text) {
        write(text == null ? "" : text, false);
    }

    public void println(String text) {
        write(text == null ? "" : text, true);
    }

    public void println() {
        write("", true);
    }

    /** 行内刷新（进度原地更新）；纯文本分支同样用回车覆盖。 */
    public void carriageReturn(String text) {
        write("\r" + (text == null ? "" : text), false);
    }

    public void flush() {
        try {
            if (writer != null) {
                writer.flush();
            } else if (fallbackStream != null) {
                fallbackStream.flush();
            }
        } catch (IOException ignored) {
            // 终端已断开：无处可报，静默
        }
    }

    @Override
    public void close() {
        flush();
    }

    private void write(String text, boolean newline) {
        try {
            if (writer != null) {
                writer.write(text);
                if (newline) {
                    writer.write(System.lineSeparator());
                }
                writer.flush();
            } else if (fallbackStream != null) {
                if (newline) {
                    fallbackStream.println(text);
                } else {
                    fallbackStream.print(text);
                }
                fallbackStream.flush();
            }
        } catch (IOException ignored) {
            // 输出失败（管道被关闭等）不应中断 Agent 运行
        }
    }
}
