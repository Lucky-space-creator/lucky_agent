package cli.channel;

import com.lucky.agent.cli.channel.EventRenderer;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.common.dto.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code options} 事件的终端渲染回归守卫。
 *
 * <p><b>为什么单独守一层</b>：内核在同进程内发的是 {@link AgentEvent.OptionItem} 记录，
 * 而渲染端曾经只判 {@code o instanceof Map} —— 结果是选项列表「有事件、没输出」：
 * 用户看到提问却看不到任何可选项，{@code CliTurnLoop} 也永远拿不到选项去做条件选择。
 * 这类「类型形状不匹配导致静默无输出」的缺陷编译期与常规断言都抓不到，必须用真实事件打通。</p>
 */
class EventRendererOptionsTest {

    private static final String SESSION = "sess-1";

    @Test
    @DisplayName("真实 options 事件（OptionItem 记录）必须渲染出每一行选项")
    void rendersOptionItemsFromKernelRecord() {
        Capture cap = new Capture();
        cap.renderer.render(AgentEvent.options(SESSION, "选哪个方案？",
                List.of(new AgentEvent.OptionItem("1", "方案甲", "最快", true),
                        new AgentEvent.OptionItem("2", "方案乙", "最稳", false)),
                300));

        String text = cap.text();
        assertTrue(text.contains("选哪个方案？"), "应渲染提问：" + text);
        assertTrue(text.contains("[1] 方案甲"), "应渲染第一项：" + text);
        assertTrue(text.contains("最快"), "应渲染第一项说明：" + text);
        assertTrue(text.contains("[2] 方案乙"), "应渲染第二项：" + text);
        assertTrue(text.contains("（推荐）"), "推荐项应有标记：" + text);
        // 恰好一处推荐标记（推荐项只有一项），防止把 Boolean null 误判成 true
        assertEquals(1, countOf(text, "（推荐）"));
    }

    @Test
    @DisplayName("recommended 为 null 时不标记推荐（Boolean 拆箱陷阱）")
    void nullRecommendedIsNotRecommended() {
        Capture cap = new Capture();
        cap.renderer.render(AgentEvent.options(SESSION, "选？",
                List.of(new AgentEvent.OptionItem("1", "甲", null, null)), 0));

        String text = cap.text();
        assertTrue(text.contains("[1] 甲"), text);
        assertFalse(text.contains("（推荐）"), "null 不得被当成推荐：" + text);
        assertFalse(text.contains("null"), "不得把 null 打印出来：" + text);
    }

    @Test
    @DisplayName("选项为空列表时只渲染提问，不抛异常")
    void emptyOptionsDoNotThrow() {
        Capture cap = new Capture();
        cap.renderer.render(AgentEvent.options(SESSION, "随便说说", List.of(), 300));
        assertTrue(cap.text().contains("随便说说"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    /** 把渲染结果落到内存的测试壳。 */
    private static final class Capture {

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final EventRenderer renderer;

        Capture() {
            this.renderer = new EventRenderer(
                    OutputSink.of(new PrintStream(buf, true, StandardCharsets.UTF_8), Theme.plain()), false);
        }

        String text() {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
