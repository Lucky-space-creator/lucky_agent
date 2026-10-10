package com.lucky.agent.cli.term;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.common.constant.WorkspaceDirs;
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
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * 交互式历史文件名（落在 {@code <frameworkRoot>/agent/} 下，纳入 D4 目录规范）。
     *
     * <p>必须显式指定，不能依赖 JLine 默认：{@code DefaultHistory} 不设默认路径，
     * 未绑定 {@code history-file} 变量时历史完全不落盘（见 {@link #jline}）。</p>
     */
    public static final String HISTORY_FILE_NAME = "cli-history";

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

    /**
     * 解析交互式历史文件路径：{@code <frameworkRoot>/agent/cli-history}（D4 目录规范）。
     *
     * <p>唯一定义处：历史落盘位置属目录规范，不允许散落在调用方。</p>
     */
    public static Path historyFile(Path frameworkRoot) {
        return frameworkRoot.resolve(WorkspaceDirs.AGENT).resolve(HISTORY_FILE_NAME);
    }

    /**
     * JLine 分支：具备历史 / 补全 / 行编辑。
     *
     * @param historyFile 历史文件路径（{@code <frameworkRoot>/agent/cli-history}）；为 {@code null} 时为纯内存历史
     */
    static LineEditor jline(Terminal terminal, OutputSink out, Path historyFile) {
        MutableCompleter completer = new MutableCompleter();
        History history = new DefaultHistory();
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .completer(completer);
        // 历史文件位置必须显式绑定：JLine 3.30 的 DefaultHistory 无默认路径，getPath() 读的是
        // reader 的 history-file 变量，未设置时返回 null，save() 随即空操作 —— 历史根本不落盘。
        if (historyFile != null) {
            Path file = historyFile.toAbsolutePath().normalize();
            try {
                Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
            } catch (IOException ignored) {
                // 目录创建失败不阻断 CLI，save() 自会降级为空操作
            }
            builder.variable(LineReader.HISTORY_FILE, file);
        }
        LineReader reader = builder.build();
        // 关键且易漏：LineReaderBuilder.history(...) 内部只做 setHistory（纯字段赋值），
        // **不会**调用 History.attach(reader)。而 DefaultHistory.getPath() 依赖 reader 非空，
        // 未 attach 时 reader == null → getPath() 返回 null → save() 空操作、load() 从不执行，
        // 即「历史静默不落盘、且不报错」。故此处必须显式 attach —— 它同时触发 load()，
        // 使「启动读回既有历史」与「退出 save 落盘」两端都生效。
        history.attach(reader);
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

    /** 当前历史条目数（纯文本分支为 0）；供诊断与测试断言历史是否真的加载/记录。 */
    int historySize() {
        return reader == null ? 0 : reader.getHistory().size();
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
