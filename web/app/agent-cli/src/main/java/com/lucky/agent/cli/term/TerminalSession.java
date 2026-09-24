package com.lucky.agent.cli.term;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 终端会话：把「用什么终端、开不开颜色、怎么读一行」这三件事收敛到一处。
 *
 * <p><b>两条路径，一个出口</b>：交互式 TTY 走 JLine（原生行编辑 / 历史 / 补全 / ANSI）；
 * 非 TTY（管道、CI、重定向）或 {@code --plain} 走纯文本 {@link OutputSink}。
 * 两条路径的输出都经 {@link OutputSink}，因此 {@code repl}/{@code channel} 包内不需要、
 * 也不允许出现 {@code System.out}。</p>
 *
 * <p><b>为什么必须探测 TTY 而不是无条件启用 JLine</b>：JLine 在非 TTY 上会退化为 dumb 终端，
 * 此时行编辑无效但会吞掉管道里的换行边界，导致 {@code echo hi | lucky} 读不到输入；
 * 而 {@code --output-format json} 这类机器可读输出更不能混入任何控制序列。</p>
 *
 * <p><b>UTF-8 强制</b>：Windows 默认代码页（GBK/936）会把中文输出写成乱码，故纯文本分支显式
 * 用 UTF-8 包装stdout，读入同样显式 UTF-8。启动脚本另加 {@code -Dfile.encoding=UTF-8} 双保险。</p>
 */
public final class TerminalSession implements Closeable {

    private final Terminal terminal;
    private final OutputSink sink;
    private final LineEditor editor;
    private final boolean interactive;

    private TerminalSession(Terminal terminal, OutputSink sink, LineEditor editor, boolean interactive) {
        this.terminal = terminal;
        this.sink = sink;
        this.editor = editor;
        this.interactive = interactive;
    }

    /**
     * 打开终端会话。
     *
     * @param plain 强制纯文本（不做任何 TTY 探测，禁用颜色与行编辑）
     */
    public static TerminalSession open(boolean plain) {
        if (!plain) {
            Terminal t = tryBuildTerminal();
            if (t != null && !isDumb(t)) {
                Theme theme = Theme.ansi();
                OutputSink sink = OutputSink.of(t.writer(), theme);
                return new TerminalSession(t, sink, LineEditor.jline(t, sink), true);
            }
            // 有意保留：可能已构建出 dumb 终端，须关闭以免占用原生句柄
            closeQuietly(t);
        }
        OutputSink sink = OutputSink.of(OutputSink.utf8Stdout(), Theme.plain());
        return new TerminalSession(null, sink, LineEditor.plain(sink), false);
    }

    /** 构建系统终端；任何失败都退化为纯文本，绝不因终端问题阻断 Agent（本机优先、可用性优先）。 */
    private static Terminal tryBuildTerminal() {
        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .jansi(false)
                    .jna(false)
                    // 显式 UTF-8：与启动脚本的 chcp 65001 / -Dfile.encoding=UTF-8 保持同一口径，
                    // 否则 JLine 可能按系统代码页（GBK/936）解释输入，中文输入即乱码。
                    .encoding(StandardCharsets.UTF_8)
                    .build();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** {@code dumb} 终端没有行列控制能力，行编辑会静默失效，故与「非 TTY」同等对待。 */
    private static boolean isDumb(Terminal t) {
        String type = t.getType();
        return type == null || "dumb".equals(type.toLowerCase(Locale.ROOT));
    }

    private static void closeQuietly(Terminal t) {
        if (t == null) {
            return;
        }
        try {
            t.close();
        } catch (IOException ignored) {
            // 关闭失败无后续影响
        }
    }

    public OutputSink sink() {
        return sink;
    }

    public LineEditor editor() {
        return editor;
    }

    /** 是否为可交互终端（决定是否启用 Ctrl+C 取消监听、状态栏原地刷新等交互特性）。 */
    public boolean interactive() {
        return interactive;
    }

    /** 终端宽度（纯文本路径返回 80，避免调用方到处判空）。 */
    public int width() {
        return terminal == null ? 80 : Math.max(40, terminal.getWidth());
    }

    @Override
    public void close() {
        sink.flush();
        if (terminal != null) {
            closeQuietly(terminal);
        }
    }
}
