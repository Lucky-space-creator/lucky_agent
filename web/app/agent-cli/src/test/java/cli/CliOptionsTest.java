package cli;

import com.lucky.agent.cli.CliOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数解析与派生语义。
 *
 * <p>重点：{@code -p} 与位置参数等价；非法输出格式要能被识别出来以便显式告警
 * （而不是默默按默认走）。run 级附加参数的键映射与优先级见 {@code CliConfigResolverTest}。</p>
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

    @Test
    @DisplayName("--auto-approve 与 --auto-pick 是相互独立的两个开关（P0-1 回归）")
    void autoApproveAndAutoPickAreIndependent() {
        CliOptions o = new CliOptions();
        new CommandLine(o).parseArgs("--auto-approve");
        assertTrue(o.autoApprove, "--auto-approve 只开授权代答");
        assertFalse(o.autoPick, "不得顺带打开条件选择");

        CliOptions o2 = new CliOptions();
        new CommandLine(o2).parseArgs("--auto-pick");
        assertFalse(o2.autoApprove, "只开条件选择时不得误开授权代答");
        assertTrue(o2.autoPick);

        CliOptions o3 = new CliOptions();
        new CommandLine(o3).parseArgs("--auto-approve", "--auto-pick");
        assertTrue(o3.autoApprove);
        assertTrue(o3.autoPick, "两个开关可同时开启");
    }

    @Test
    @DisplayName("--output-format 未显式给出时为 null（否则缺省值会顶掉项目配置层，P0-2 回归）")
    void outputFormatNullWhenNotGiven() {
        CliOptions o = new CliOptions();
        new CommandLine(o).parseArgs("--plain");
        assertNull(o.outputFormat, "未给出 --output-format 必须保持 null，交给三级解析决定");

        CliOptions o2 = new CliOptions();
        new CommandLine(o2).parseArgs("--output-format", "json");
        assertEquals("json", o2.outputFormat);
    }
}
