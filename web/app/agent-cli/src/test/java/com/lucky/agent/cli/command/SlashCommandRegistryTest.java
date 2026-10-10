package com.lucky.agent.cli.command;

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
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.metrics.SessionMetrics;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "/history", "/workspace", "/tokens", "/compact", "/undo", "/safety", "/reload",
                "/doctor", "/clear", "/mcp", "/skills")) {
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

    @Test
    @DisplayName("/workspace <序号> 按展示编号切换（此前只认 ID → 照提示敲必然 NOT_FOUND）")
    void workspaceAcceptsIndex() {
        StringBuilder hit = new StringBuilder();
        dispatch("/workspace 1", hit);
        String hitText = hit.toString();
        assertFalse(hitText.contains("未找到匹配的工作空间"), "序号 1 应被识别为列表第 1 项：" + hitText);
        assertTrue(hitText.contains("切换失败") || hitText.contains("已切换工作空间"),
                "应进入切换分支（本上下文未装配会话服务，故通常显示切换失败）：" + hitText);

        // 对照：越界序号必须回落为「未找到」，证明序号分支确实在起作用而非全盘放行
        StringBuilder miss = new StringBuilder();
        dispatch("/workspace 9", miss);
        assertTrue(miss.toString().contains("未找到匹配的工作空间"), miss.toString());
    }

    @Test
    @DisplayName("/safety 展示两轴安全模型（沙箱 × 审批）与当前生效策略")
    void safetyShowsTwoAxisModel() {
        StringBuilder sink = new StringBuilder();
        dispatch("/safety", sink);
        String text = sink.toString();
        assertTrue(text.contains("两轴安全模型"), text);
        assertTrue(text.contains("X 轴"), text);
        assertTrue(text.contains("Y 轴"), text);
        assertTrue(text.contains("工作空间：示例项目"), text);
        assertTrue(text.contains("权限档：全部权限"), text);
        assertTrue(text.contains("自动代答：关闭"), text);
    }

    @Test
    @DisplayName("/tokens 展示会话累计用量与上下文窗口占用率（P2-4）")
    void tokensShowsCumulativeAndWindowUsage() {
        SessionMetrics sm = new SessionMetrics();
        sm.addToken(1234);
        sm.addInput(500);
        sm.addOutput(734);
        sm.incModelCalls();
        sm.recordCache(true);
        sm.recordCache(false);
        sm.recordTool("read_file", true);
        sm.recordSkill();
        sm.recordMcp();
        sm.setLastModel("gpt-4o");
        sm.setContextWindow(128000);
        sm.setStartAt(System.currentTimeMillis() - 1500);
        MetricsCollector metrics = new MetricsCollector(null, null) {
            @Override
            public SessionMetrics snapshot(String sessionId) {
                return sm;
            }
        };
        StringBuilder sink = new StringBuilder();
        SlashCommandRegistry.withBuiltins().dispatch("/tokens", context(sink, metrics));
        String text = sink.toString();
        assertTrue(text.contains("会话累计用量"), text);
        assertTrue(text.contains("1234"), text);
        assertTrue(text.contains("gpt-4o"), text);
        assertTrue(text.contains("128000"), text);
        // 1234 / 128000 ≈ 0.96%，按 %.1f 四舍五入显示为 1.0%
        assertTrue(text.contains("1.0%"), text);
    }

    @Test
    @DisplayName("自定义命令：commands/*.md 扫描为 /name 且正文作为待提交提示词")
    void customCommandScannedAndPending(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("review.md");
        Files.writeString(file, "代码评审\n\n请评审以下改动：\n- 新增了 X\n");
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        registry.registerCustomCommands(dir);

        StringBuilder sink = new StringBuilder();
        CommandContext ctx = context(sink);
        registry.dispatch("/review", ctx);
        String pending = ctx.consumePendingPrompt();
        assertTrue(pending != null && pending.contains("请评审以下改动"), "自定义命令正文应成为待提交提示词");
    }

    @Test
    @DisplayName("自定义命令不覆盖同名内置命令（决策 1：能力边界锁死）")
    void customCommandDoesNotShadowBuiltin(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("help.md");
        Files.writeString(file, "伪装成 help 的自定义命令");
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        registry.registerCustomCommands(dir);

        StringBuilder sink = new StringBuilder();
        registry.dispatch("/help", context(sink));
        // 内置 /help 仍生效：列出全部命令，而不是被自定义「help」替换成一句话
        assertTrue(sink.toString().contains("/exit"), "内置 /help 不应被同名自定义命令覆盖");
    }

    @Test
    @DisplayName("/reload 重新读取项目本地配置并写入上下文（改完保存即生效）")
    void reloadReadsProjectConfig(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve(".lucky/settings.json");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "{\"model\":\"reload-model\",\"maxTurns\":7}");
        // frameworkRoot 放在 cwd 子目录外，避免触发「起始目录位于框架根内 → 跳过」分支
        WorkspaceDirs dirs = new WorkspaceDirs() {
            @Override
            public Path frameworkRoot() {
                return dir.resolve("fw");
            }
        };
        StringBuilder sink = new StringBuilder();
        CommandContext ctx = context(sink, null, dirs, dir.toString());

        SlashCommandRegistry.withBuiltins().dispatch("/reload", ctx);

        String text = sink.toString();
        assertTrue(text.contains("已重载项目配置"), text);
        assertTrue(text.contains("reload-model"), text);
        assertEquals("reload-model", ctx.project().values().get("model"), "重载后上下文配置应已更新");
        assertEquals("7", ctx.project().values().get("maxTurns"));
    }

    // ------------------------------------------------------------------ /mcp · /skills

    @Test
    @DisplayName("/mcp 列出服务与连接状态；未连接时不触发连接去数工具")
    void mcpListsServers() {
        StringBuilder sink = new StringBuilder();
        CliServices services = extensionServices(
                new StubMcpRegistry(List.of(new McpServerDef("fs", "文件系统", "本机文件读写",
                        McpServerDef.TYPE_STDIO, "npx", List.of("-y", "server-fs"), null, Map.of(), true))),
                new StubMcpConnector(McpConnector.STATUS_STOPPED, false),
                null);

        SlashCommandRegistry.withBuiltins().dispatch("/mcp", context(sink, services));

        String text = sink.toString();
        assertTrue(text.contains("MCP 服务"), text);
        assertTrue(text.contains("fs") && text.contains("文件系统"), text);
        assertTrue(text.contains("未连接"), text);
        assertFalse(text.contains("（工具"), "未连接时不得为看状态而把服务拉起来：" + text);
    }

    @Test
    @DisplayName("/skills 列出技能与启用状态，并标记缺依赖")
    void skillsListsSkills() {
        SkillDef platform = new SkillDef("pdf", "PDF 处理", "处理 PDF 文件", List.of("pdf"),
                SkillDef.TYPE_TOOL, true, true, List.of(), null, SkillDef.SOURCE_PLATFORM);
        SkillDef custom = new SkillDef("draw", "绘图", "画示意图", List.of("draw"),
                SkillDef.TYPE_SUBAGENT, false, false, List.of("ghost"), null, SkillDef.SOURCE_USER)
                .withMissingDeps(List.of("ghost"));
        StringBuilder sink = new StringBuilder();
        CliServices services = extensionServices(null, null, new StubSkillRegistry(List.of(platform, custom)));

        SlashCommandRegistry.withBuiltins().dispatch("/skills", context(sink, services));

        String text = sink.toString();
        assertTrue(text.contains("技能（共 2 个，启用 1）"), text);
        assertTrue(text.contains("pdf") && text.contains("预置"), text);
        assertTrue(text.contains("draw") && text.contains("子代理"), text);
        assertTrue(text.contains("缺依赖"), text);
    }

    @Test
    @DisplayName("registry 未装配时 /mcp /skills 明确提示而非抛异常")
    void extensionCommandsWithoutRegistry() {
        StringBuilder sink = new StringBuilder();
        SlashCommandRegistry.withBuiltins().dispatch("/mcp", context(sink));
        assertTrue(sink.toString().contains("未装配"), sink.toString());
        sink.setLength(0);
        SlashCommandRegistry.withBuiltins().dispatch("/skills", context(sink));
        assertTrue(sink.toString().contains("未装配"), sink.toString());
    }

    @Test
    @DisplayName("命令主题分组：菜单据此归类，未登记归「其他」")
    void commandGroups() {
        SlashCommandRegistry r = SlashCommandRegistry.withBuiltins();
        assertEquals("会话", r.groupOf("resume"));
        assertEquals("环境", r.groupOf("doctor"));
        assertEquals("扩展", r.groupOf("mcp"));
        assertEquals("扩展", r.groupOf("skills"));
        assertEquals("其他", r.groupOf("some-custom"));
    }

    // ------------------------------------------------------------------ 辅助

    private static SlashCommandRegistry.Outcome dispatch(String line, StringBuilder sink) {
        return SlashCommandRegistry.withBuiltins().dispatch(line, context(sink));
    }

    private static CommandContext context(StringBuilder sink) {
        return context(sink, null, null, null);
    }

    private static CommandContext context(StringBuilder sink, MetricsCollector metrics) {
        return context(sink, metrics, null, null);
    }

    private static CommandContext context(StringBuilder sink, MetricsCollector metrics,
                                          WorkspaceDirs dirs, String cwd) {
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
        CliServices services = new CliServices(null, null, null, workspaces, dirs, metrics, null,
                null, new CliConfigResolver(), null, null, null);
        CliConfigResolver.ProjectConfig project =
                new CliConfigResolver.ProjectConfig(null, Map.of(), List.of(), false);
        SessionHolder holder = new SessionHolder(new SessionRef("s-12345678", "local-user", "ws-1"));
        CliOptions options = new CliOptions();
        options.cwd = cwd;
        return new CommandContext(out, services, options, project, holder, () -> {
        });
    }

    /** 用给定 services 构造上下文（供 /mcp、/skills 注入测试桩）。 */
    private static CommandContext context(StringBuilder sink, CliServices services) {
        OutputSink out = OutputSink.of(
                new PrintStream(new StringBuilderStream(sink), true, StandardCharsets.UTF_8), Theme.plain());
        CliConfigResolver.ProjectConfig project =
                new CliConfigResolver.ProjectConfig(null, Map.of(), List.of(), false);
        SessionHolder holder = new SessionHolder(new SessionRef("s-12345678", "local-user", "ws-1"));
        return new CommandContext(out, services, new CliOptions(), project, holder, () -> {
        });
    }

    /** 组装只带扩展 registry 的 CliServices（其余留空）。 */
    private static CliServices extensionServices(com.lucky.agent.mcp.api.McpRegistry mcp,
                                                 com.lucky.agent.mcp.api.McpConnector connector,
                                                 com.lucky.agent.skill.service.SkillRegistry skills) {
        return new CliServices(null, null, null, null, null, null, null, null,
                new CliConfigResolver(), mcp, connector, skills);
    }

    /** 测试桩：只实现 /mcp 用到的方法，其余抛异常以暴露越界调用。 */
    private static final class StubMcpRegistry implements com.lucky.agent.mcp.api.McpRegistry {
        private final List<com.lucky.agent.mcp.api.dto.McpServerDef> defs;

        StubMcpRegistry(List<com.lucky.agent.mcp.api.dto.McpServerDef> defs) {
            this.defs = defs;
        }

        @Override
        public List<com.lucky.agent.mcp.api.dto.McpServerDef> list() {
            return defs;
        }

        @Override
        public List<com.lucky.agent.mcp.api.dto.McpServerDef> listEnabled() {
            return defs.stream().filter(com.lucky.agent.mcp.api.dto.McpServerDef::enabled).toList();
        }

        @Override
        public Optional<com.lucky.agent.mcp.api.dto.McpServerDef> find(String id) {
            return defs.stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public void setEnabled(String id, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.lucky.agent.mcp.api.dto.McpServerDef save(
                com.lucky.agent.mcp.api.dto.McpServerDef def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.lucky.agent.mcp.api.dto.McpServerDef importFrom(Path packageDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reload() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long revision() {
            return 0;
        }
    }

    /** 测试桩：只实现读状态用到的方法。 */
    private static final class StubMcpConnector implements com.lucky.agent.mcp.api.McpConnector {
        private final String status;
        private final boolean connected;

        StubMcpConnector(String status, boolean connected) {
            this.status = status;
            this.connected = connected;
        }

        @Override
        public String status(String serverId) {
            return status;
        }

        @Override
        public boolean isConnected(String serverId) {
            return connected;
        }

        @Override
        public List<com.lucky.agent.mcp.api.dto.McpTool> listTools(String serverId) {
            return List.of();
        }

        @Override
        public void connect(String serverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close(String serverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reconnect(String serverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean ping(String serverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.lucky.agent.common.dto.ToolResult callTool(String serverId, String toolName,
                                                              Map<String, Object> args,
                                                              com.lucky.agent.common.dto.ConversationCtx ctx) {
            throw new UnsupportedOperationException();
        }
    }

    /** 测试桩：只实现 /skills 用到的方法。 */
    private static final class StubSkillRegistry implements com.lucky.agent.skill.service.SkillRegistry {
        private final List<com.lucky.agent.skill.repository.dto.SkillDef> defs;

        StubSkillRegistry(List<com.lucky.agent.skill.repository.dto.SkillDef> defs) {
            this.defs = defs;
        }

        @Override
        public List<com.lucky.agent.skill.repository.dto.SkillDef> list() {
            return defs;
        }

        @Override
        public List<com.lucky.agent.skill.repository.dto.SkillDef> listEnabled() {
            return defs.stream().filter(com.lucky.agent.skill.repository.dto.SkillDef::enabled).toList();
        }

        @Override
        public Optional<com.lucky.agent.skill.repository.dto.SkillDef> find(String id) {
            return defs.stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public void setEnabled(String id, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isReadOnly(String id) {
            return false;
        }

        @Override
        public com.lucky.agent.skill.repository.dto.SkillDef save(
                com.lucky.agent.skill.repository.dto.SkillDef def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.lucky.agent.skill.repository.dto.SkillDef importFrom(Path packageDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.lucky.agent.skill.repository.dto.SkillDef importFromDir(Path sourceDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reload() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long revision() {
            return 0;
        }
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
