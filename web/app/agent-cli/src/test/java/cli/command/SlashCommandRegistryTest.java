package cli.command;

import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.command.CliServices;
import com.lucky.agent.cli.command.CommandContext;
import com.lucky.agent.cli.command.SlashCommand;
import com.lucky.agent.cli.command.SlashCommandRegistry;
import com.lucky.agent.cli.config.CliConfigResolver;
import com.lucky.agent.cli.session.SessionHolder;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * slash 命令分发。
 *
 * <p>覆盖三件事：分发前缀判定、命令的可发现性（{@code /help} 与补全同源）、
 * 以及决策 1 的能力边界 —— CLI 不接工作流，故不存在 {@code /run} 与 {@code /workflows}。</p>
 */
class SlashCommandRegistryTest {

    @Test
    @DisplayName("非 slash 输入不归命令层处理")
    void notACommand() {
        assertEquals(SlashCommandRegistry.Outcome.NOT_A_COMMAND, dispatch("hello", new StringBuilder()));
        assertEquals(SlashCommandRegistry.Outcome.NOT_A_COMMAND, dispatch("", new StringBuilder()));
    }

    @Test
    @DisplayName("未知命令给出明确提示（不静默当成提示词发给模型）")
    void unknownCommand() {
        StringBuilder sink = new StringBuilder();
        assertEquals(SlashCommandRegistry.Outcome.UNKNOWN, dispatch("/nope", sink));
        assertTrue(sink.toString().contains("未知命令"), sink.toString());
    }

    @Test
    @DisplayName("/exit 请求退出（返回 EXIT 而不是直接杀进程）")
    void exitRequestsExit() {
        assertEquals(SlashCommandRegistry.Outcome.EXIT, dispatch("/exit", new StringBuilder()));
        assertEquals(SlashCommandRegistry.Outcome.EXIT, dispatch("/quit", new StringBuilder()));
    }

    @Test
    @DisplayName("/help 列出全部命令，且与补全候选同源")
    void helpListsEveryCommand() {
        StringBuilder sink = new StringBuilder();
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        registry.dispatch("/help", context(sink));
        String text = sink.toString();

        for (String name : List.of("/help", "/exit", "/new", "/sessions", "/resume",
                "/history", "/workspace", "/tokens", "/doctor", "/clear")) {
            assertTrue(text.contains(name), "帮助里应包含 " + name);
        }
        // 每个命令名与别名都应可被补全（否则会出现「敲得出来但补不出来」）
        List<String> candidates = registry.completions("/");
        for (SlashCommand c : registry.commands()) {
            assertTrue(candidates.contains("/" + c.name()), "补全缺主名 " + c.name());
            for (String alias : c.aliases()) {
                assertTrue(candidates.contains("/" + alias), "补全缺别名 " + alias);
            }
        }
    }

    @Test
    @DisplayName("决策 1：CLI 不提供工作流命令")
    void noWorkflowCommands() {
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        List<String> all = registry.completions("/");
        // 注意：不能用 completions("/work") 判空——/workspace 是合法命令，天然以 /work 为前缀
        assertFalse(all.contains("/workflows"), "不应存在 /workflows");
        assertFalse(all.contains("/run"), "不应存在 /run");
        assertEquals(SlashCommandRegistry.Outcome.UNKNOWN, dispatch("/workflows", new StringBuilder()));
    }

    @Test
    @DisplayName("dispatch 自回填 registry：未显式回填时 /help 不 NPE")
    void dispatchBackfillsRegistry() {
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        CommandContext ctx = context(new StringBuilder());
        assertNull(ctx.registry(), "前置：上下文未回填");
        assertEquals(SlashCommandRegistry.Outcome.CONTINUE, registry.dispatch("/help", ctx));
        assertNotNull(ctx.registry(), "/help 执行后应已回填");
    }

    @Test
    @DisplayName("补全按前缀过滤")
    void completionsFilterByPrefix() {
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        assertEquals(List.of("/sessions"), registry.completions("/sess"));
        assertTrue(registry.completions("/zzz").isEmpty());
    }

    @Test
    @DisplayName("/clear 在无 ANSI 的纯文本模式下不输出控制序列")
    void clearIsSafeInPlainMode() {
        StringBuilder sink = new StringBuilder();
        dispatch("/clear", sink);
        assertFalse(sink.toString().contains("\u001B"), "纯文本模式不得输出 ANSI 转义");
    }

    @Test
    @DisplayName("/workspace 无参数时无条件回显当前工作空间（用户必须知道 Agent 在哪个目录干活）")
    void workspaceShowsCurrent() {
        StringBuilder sink = new StringBuilder();
        dispatch("/workspace", sink);
        String text = sink.toString();
        assertTrue(text.contains("当前工作空间"), text);
        assertTrue(text.contains("/data/proj"), text);
        assertTrue(text.contains("全部权限"), text);
    }

    // ------------------------------------------------------------------ 辅助

    private static SlashCommandRegistry.Outcome dispatch(String line, StringBuilder sink) {
        return SlashCommandRegistry.withBuiltins().dispatch(line, context(sink));
    }

    private static CommandContext context(StringBuilder sink) {
        OutputSink out = OutputSink.of(
                new PrintStream(new StringBuilderStream(sink), true, StandardCharsets.UTF_8), Theme.plain());
        Workspace ws = new Workspace().workspaceId("ws-1").name("示例项目")
                .path("/data/proj").permissionLevel(PermissionLevel.FULL);
        WorkspaceConfig workspaces = new WorkspaceConfig() {
            @Override
            public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
                return Optional.of(PermissionLevel.FULL);
            }

            @Override
            public Optional<String> physicalPathOf(String workspaceId) {
                return Optional.of("/data/proj");
            }

            @Override
            public Optional<Workspace> getWorkspace(String workspaceId) {
                return "ws-1".equals(workspaceId) ? Optional.of(ws) : Optional.empty();
            }

            @Override
            public List<Workspace> listWorkspaces() {
                return List.of(ws);
            }
        };
        // 只填本组用例真正会用的依赖；其余留空以暴露「命令偷偷用了不该用的东西」
        CliServices services = new CliServices(null, null, null, workspaces, null, null, null,
                null, new CliConfigResolver());
        CliConfigResolver.ProjectConfig project =
                new CliConfigResolver.ProjectConfig(null, Map.of(), List.of(), false);
        SessionHolder holder = new SessionHolder(new SessionRef("s-12345678", "local-user", "ws-1"));
        return new CommandContext(out, services, new CliOptions(), project, holder, () -> {
        });
    }

    /** 把 PrintStream 写到 StringBuilder 的桥（避免测试里到处字节转字符串）。 */
    private static final class StringBuilderStream extends java.io.OutputStream {

        private final StringBuilder target;

        StringBuilderStream(StringBuilder target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            target.append((char) b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            target.append(new String(b, off, len, StandardCharsets.UTF_8));
        }
    }
}
