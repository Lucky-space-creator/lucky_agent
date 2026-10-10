package com.lucky.agent.cli.term;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7-2 回归：交互式历史必须显式落盘到 {@code <frameworkRoot>/agent/cli-history}。
 *
 * <p>JLine 3.30 有两个必须同时满足的点，任一缺失都会让历史<b>静默不落盘</b>（不报错）：</p>
 * <ol>
 *   <li>{@code DefaultHistory} 无默认路径 —— 必须绑定 {@code history-file} 变量；</li>
 *   <li>{@code LineReaderBuilder.history(...)} 只做 {@code setHistory}（纯字段赋值），
 *       <b>不会</b>调用 {@code History.attach(reader)}；未 attach 时 {@code reader == null}，
 *       {@code getPath()} 返回 {@code null}，{@code load()/save()} 均空操作。</li>
 * </ol>
 *
 * <p>本测试用 dumb 终端 + 显式内存流（不触碰真实控制台），锁定「路径符合 D4」与「确实写入并可回读」。</p>
 */
class LineEditorHistoryTest {

    @Test
    @DisplayName("historyFile 解析到 <frameworkRoot>/agent/cli-history（D4 目录规范）")
    void historyFilePathFollowsD4Convention() {
        Path root = Path.of("/tmp/fw");
        assertEquals(root.resolve("agent").resolve("cli-history"), LineEditor.historyFile(root));
    }

    @Test
    @DisplayName("JLine 分支把历史写入指定文件（不再静默不落盘）")
    void jlinePersistsHistoryToGivenFile(@TempDir Path tmp) throws Exception {
        Path hist = LineEditor.historyFile(tmp);
        Terminal terminal = newDumbTerminal();
        OutputSink out = OutputSink.of(new PrintWriter(new StringWriter()), Theme.plain());
        try {
            try (LineEditor editor = LineEditor.jline(terminal, out, hist)) {
                editor.addHistory("/tokens");
                editor.addHistory("/status");
            }
            assertTrue(Files.exists(hist), "历史文件应落盘到指定路径：" + hist);
            String content = Files.readString(hist);
            assertTrue(content.contains("/tokens"), "落盘内容应含 /tokens，实际：" + content);
            assertTrue(content.contains("/status"), "落盘内容应含 /status，实际：" + content);
        } finally {
            terminal.close();
        }
    }

    @Test
    @DisplayName("再次打开时能读回既有历史（attach 触发 load）")
    void jlineReloadsExistingHistory(@TempDir Path tmp) throws Exception {
        Path hist = LineEditor.historyFile(tmp);

        Terminal first = newDumbTerminal();
        OutputSink out = OutputSink.of(new PrintWriter(new StringWriter()), Theme.plain());
        try (LineEditor editor = LineEditor.jline(first, out, hist)) {
            editor.addHistory("/doctor");
        } finally {
            first.close();
        }

        Terminal second = newDumbTerminal();
        try (LineEditor editor = LineEditor.jline(second, out, hist)) {
            assertTrue(editor.historySize() >= 1, "新一轮应读回至少 1 条历史");
        } finally {
            second.close();
        }
    }

    /** dumb 终端 + 内存流：不依赖真实 TTY，避免测试机/CI 上的控制台探测开销。 */
    private static Terminal newDumbTerminal() throws Exception {
        return TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
    }
}
