package cli;

import com.lucky.agent.cli.CliOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数解析与派生语义。
 *
 * <p>重点：{@code -p} 与位置参数等价；{@code runExtra()} 的键名必须与内核读取口径一致
 * （{@code modelId} / {@code maxTurns} / {@code maxBudget}）；非法输出格式要能被识别出来
 * 以便显式告警（而不是默默按默认走）。</p>
 */
class CliOptionsTest {

    @Test
    @DisplayName("-p 与位置参数等价，都表示 headless")
    void promptFromFlagOrPositional() {
        CliOptions a = new CliOptions();
        a.prompt = "你好";
        assertTrue(a.headless());
        assertEquals("你好", a.promptText());

        CliOptions b = new CliOptions();
        b.positional = List.of("帮我", "看下", "这个文件");
        assertTrue(b.headless());
        assertEquals("帮我 看下 这个文件", b.promptText());
    }

    @Test
    @DisplayName("两者都没给 → 进入交互式 REPL")
    void noPromptMeansRepl() {
        CliOptions o = new CliOptions();
        assertFalse(o.headless());
        assertNull(o.promptText());
    }

    @Test
    @DisplayName("-p 优先于位置参数")
    void flagWinsOverPositional() {
        CliOptions o = new CliOptions();
        o.prompt = "来自 flag";
        o.positional = List.of("来自位置参数");
        assertEquals("来自 flag", o.promptText());
    }

    @Test
    @DisplayName("空白位置参数不构成提示词（避免纯空格参数进入 headless 空跑）")
    void blankPositionalIsNotPrompt() {
        CliOptions o = new CliOptions();
        o.positional = List.of("   ");
        assertNull(o.promptText());
        assertFalse(o.headless());
    }

    @Test
    @DisplayName("runExtra 只放内核认识的白名单键，且不含空值")
    void runExtraUsesKernelKeys() {
        CliOptions o = new CliOptions();
        o.model = " deepseek-chat ";
        o.maxTurns = "12";
        o.maxBudget = "100000";
        Map<String, Object> extra = o.runExtra();

        assertEquals("deepseek-chat", extra.get("modelId"), "必须带 modelId 键名（内核按此键读取）");
        assertEquals("12", extra.get("maxTurns"));
        assertEquals("100000", extra.get("maxBudget"));
        assertEquals(3, extra.size());

        CliOptions empty = new CliOptions();
        assertTrue(empty.runExtra().isEmpty(), "未指定时不得塞入空串占位");
    }

    @Test
    @DisplayName("输出格式：合法值原样规范化，非法值可被识别（用于显式告警）")
    void outputFormatNormalization() {
        CliOptions o = new CliOptions();
        o.outputFormat = "JSON";
        assertEquals("json", o.normalizedOutputFormat());
        assertTrue(o.outputFormatValid());

        o.outputFormat = "yaml";
        assertEquals("text", o.normalizedOutputFormat(), "非法值回落 text");
        assertFalse(o.outputFormatValid(), "必须能识别出非法值，否则用户永远不知道为什么没生效");

        o.outputFormat = null;
        assertEquals("text", o.normalizedOutputFormat());
        assertTrue(o.outputFormatValid());
    }
}
