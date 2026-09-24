package com.lucky.agent.cli.term;

import com.lucky.agent.cli.channel.OutputSink;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 读一行输入。交互式走 JLine（历史 / 补全 / 行编辑），非交互式走缓冲读取。
 *
 * <p><b>两种「取消」语义必须分开</b>：</p>
 * <ul>
 *   <li>{@code Ctrl+C} 在提示符处 → 清空当前行、重新提示（用户想撤销这一行，不想退出）。
 *       连续两次（中间没有成功读到任何一行）才视为「退出」——这是主流 CLI 的一致行为，
 *       也是防止误触杀进程的必要缓冲。</li>
 *   <li>{@code Ctrl+D}（或管道结束 / Windows 下 {@code Ctrl+Z} 回车）→ 返回 {@code null}，调用方退出。</li>
 * </ul>
 *
 * <p>运行期（非提示符状态）的 {@code Ctrl+C} 由 {@code CliTurnExecutor} 的监听线程处理，
 * 与本类无关：那时主线程阻塞在提交上，不持有行编辑区。</p>
 */
public final class LineEditor implements InputReader, Closeable {

    /** 提示符处按 Ctrl+C 且这是连续第二次中断时返回，表示「用户想退出」。 */
    public static final String EXIT = "\u0000__EXIT__";

    private final OutputSink out;
    private final LineReader reader;
    private final BufferedReader plainIn;

    /** 可替换的补全器：行读取器在构建时就绑定它，后续通过 {@link #setCompleter} 换实现。 */
    private final MutableCompleter completer;

    /** 上一行是否以「中断退出」告终（用于连续两次 Ctrl+C 判定）。 */
    private boolean lastWasInterrupt;

    private LineEditor(OutputSink out, LineReader reader, BufferedReader plainIn, MutableCompleter completer) {
        this.out = out;
        this.reader = reader;
        this.plainIn = plainIn;
        this.completer = completer;
    }

    /** JLine 分支：具备历史 / 补全 / 行编辑。 */
    static LineEditor jline(Terminal terminal, OutputSink out) {
        MutableCompleter completer = new MutableCompleter();
        History history = new DefaultHistory();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .completer(completer)
                .build();
        return new LineEditor(out, reader, null, completer);
    }

    /** 纯文本分支：无行编辑，逐行读取；提示符照常打印，保证管道场景下输出可读。 */
    static LineEditor plain(OutputSink out) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new LineEditor(out, null, in, new MutableCompleter());
    }

    /**
     * 读一行。
     *
     * @param prompt 提示符（JLine 分支由终端绘制，纯文本分支直接打印）
     * @return 用户输入行；{@code null} 表示输入结束；{@link #EXIT} 表示用户连续两次要求退出
     */
    @Override
    public String readLine(String prompt) {
        if (reader != null) {
            try {
                String line = reader.readLine(prompt);
                lastWasInterrupt = false;
                return line;
            } catch (UserInterruptException e) {
                if (lastWasInterrupt) {
                    return EXIT;
                }
                lastWasInterrupt = true;
                out.println(out.theme().dim("^C  （再按一次 Ctrl+C 退出，或输入 /exit）"));
                return "";
            } catch (EndOfFileException e) {
                return null;
            }
        }
        out.print(prompt);
        out.flush();
        try {
            String line = plainIn.readLine();
            lastWasInterrupt = false;
            return line;
        } catch (IOException e) {
            return null;
        }
    }

    /** 记入历史（仅交互式生效）；空白行不记。 */
    public void addHistory(String line) {
        if (reader == null || line == null || line.isBlank()) {
            return;
        }
        try {
            reader.getHistory().add(line);
        } catch (Exception ignored) {
            // 历史写盘失败不应影响主流程
        }
    }

    /** 设置补全器（JLine 分支生效）。 */
    public void setCompleter(Completer c) {
        completer.set(c);
    }

    @Override
    public void close() {
        if (reader != null) {
            try {
                reader.getHistory().save();
            } catch (IOException ignored) {
                // 历史落盘失败无影响
            }
        }
    }

    /** 可替换补全器：允许先建终端、后建命令注册表（两者存在构造期循环依赖）。 */
    private static final class MutableCompleter implements Completer {

        private volatile Completer delegate;

        void set(Completer c) {
            this.delegate = c;
        }

        @Override
        public void complete(org.jline.reader.LineReader reader, org.jline.reader.ParsedLine line,
                             java.util.List<org.jline.reader.Candidate> candidates) {
            Completer d = delegate;
            if (d != null) {
                d.complete(reader, line, candidates);
            }
        }
    }
}
