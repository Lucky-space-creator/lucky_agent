package cli.approval;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import com.lucky.agent.cli.term.InputReader;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASK 授权裁决 —— 本项目的 P0 安全缺陷（CLI 方案 v1 §3 P0-2）就修在这里。
 *
 * <p>最关键的一条断言是 {@link #allowOnceReturnsTheVerbatimOp()}：旧实现回传的是自行拼接的
 * {@code {opType:"WRITE", path:"", args:{}}}，与用户被询问的操作无关，等于「按一次 y 放行
 * 另一件事」。这条用例锁死「回传必须与被询问的 op 是同一个对象」。</p>
 */
class ApprovalHandlerTest {

    private static final String WS = "ws-1";
    private static final String WS_ROOT = "/ws";

    // ------------------------------------------------------------------ 核心契约

    @Test
    @DisplayName("P0-2：允许时回传的 confirm 必须是被询问的那个 op 本身")
    void allowOnceReturnsTheVerbatimOp() {
        ApprovalHandler h = handler(new ScriptedReader("y"), null, false);
        Map<String, Object> op = new HashMap<>();
        op.put("opType", "DELETE");
        op.put("path", "old/Report.md");
        op.put("args", Map.of("recursive", true));

        ApprovalHandler.Verdict v = h.ask(op, "确认删除？", "HIGH", WS);

        assertTrue(v.allowed());
        assertSame(op, v.confirm(), "回传必须是同一个 op 对象，不得重建或裁剪字段");
        assertEquals(ApprovalHandler.Choice.ALLOW_ONCE, v.choice());
    }

    @Test
    @DisplayName("缺少 op 时按拒绝处理（不猜一个默认操作）")
    void missingOpIsDenied() {
        ApprovalHandler h = handler(new ScriptedReader("y"), null, false);
        ApprovalHandler.Verdict v = h.ask(null, "?", "HIGH", WS);
        assertFalse(v.allowed());
        assertNull(v.confirm());
        assertEquals(ApprovalHandler.Choice.DENY, v.choice());
    }

    @Test
    @DisplayName("op 为空 Map 同样拒绝")
    void emptyOpIsDenied() {
        ApprovalHandler h = handler(new ScriptedReader("y"), null, false);
        assertFalse(h.ask(Map.of(), "?", "HIGH", WS).allowed());
    }

    // ------------------------------------------------------------------ 四档

    @Test
    @DisplayName("直接回车 = 拒绝（默认档是拒绝，不是允许）")
    void blankAnswerDenies() {
        ApprovalHandler h = handler(new ScriptedReader(""), null, false);
        ApprovalHandler.Verdict v = h.ask(op("WRITE", "a.txt"), "?", "HIGH", WS);
        assertFalse(v.allowed());
        assertEquals(ApprovalHandler.Choice.DENY, v.choice());
    }

    @Test
    @DisplayName("无法识别的输入会重问，直到给出有效选择")
    void unknownInputReasks() {
        ScriptedReader reader = new ScriptedReader("lol", "maybe", "y");
        ApprovalHandler h = handler(reader, null, false);
        ApprovalHandler.Verdict v = h.ask(op("WRITE", "a.txt"), "?", "HIGH", WS);
        assertTrue(v.allowed());
        assertEquals(3, reader.asked(), "应重问两次后再接受 y");
    }

    @Test
    @DisplayName("a：本会话内同 (操作类型, 路径) 不再询问；同路径不同操作类型仍会询问")
    void sessionAllowIsScopedByOpTypeAndPath() {
        ScriptedReader reader = new ScriptedReader("a", "y");
        ApprovalHandler h = handler(reader, null, false);

        ApprovalHandler.Verdict first = h.ask(op("WRITE", "a.txt"), "?", "HIGH", WS);
        assertEquals(ApprovalHandler.Choice.ALLOW_SESSION, first.choice());

        // 同 opType + 同 path：不再消耗输入（reader 只有一条剩余答案 "y" 留给后面的 DELETE）
        ApprovalHandler.Verdict again = h.ask(op("WRITE", "a.txt"), "?", "HIGH", WS);
        assertTrue(again.allowed());
        assertEquals(1, reader.asked(), "第二次不应再问用户");

        // 同 path 但操作类型不同（DELETE）：必须重新询问 —— 否则「允许写」会连带放行「删除」
        ApprovalHandler.Verdict delete = h.ask(op("DELETE", "a.txt"), "?", "HIGH", WS);
        assertTrue(delete.allowed());
        assertEquals(2, reader.asked());
    }

    @Test
    @DisplayName("a：内核未给操作类型时无法界定范围，拒绝该档并重问")
    void sessionAllowRefusedWithoutOpType() {
        ScriptedReader reader = new ScriptedReader("a", "y");
        ApprovalHandler h = handler(reader, null, false);
        Map<String, Object> noType = new HashMap<>();
        noType.put("path", "a.txt");
        ApprovalHandler.Verdict v = h.ask(noType, "?", "HIGH", WS);
        assertTrue(v.allowed(), "重问后由 y 放行");
        assertEquals(ApprovalHandler.Choice.ALLOW_ONCE, v.choice());
        assertEquals(2, reader.asked());
    }

    @Test
    @DisplayName("--auto-approve：直接放行且回传原 op（用于脚本，仍受执行臂约束）")
    void autoApproveShortCircuits() {
        ScriptedReader reader = new ScriptedReader("n");
        ApprovalHandler h = handler(reader, null, true);
        Map<String, Object> op = op("EXEC", "rm -rf build");
        ApprovalHandler.Verdict v = h.ask(op, "?", "HIGH", WS);
        assertTrue(v.allowed());
        assertSame(op, v.confirm());
        assertEquals(0, reader.asked(), "自动放行不应再问用户");
        assertFalse(h.interactive());
    }

    @Test
    @DisplayName("非交互环境（无输入源）：拒绝，不挂起等待")
    void nonInteractiveDenies() {
        ApprovalHandler h = handler(null, null, false);
        ApprovalHandler.Verdict v = h.ask(op("DELETE", "a.txt"), "?", "HIGH", WS);
        assertFalse(v.allowed());
        assertFalse(h.interactive());
    }

    // ------------------------------------------------------------------ 持久规则

    @Test
    @DisplayName("r：二次确认后写入精确匹配的 ALLOW 规则，且已有规则不被覆盖")
    void persistRuleMergesExistingRules() {
        RecordingPermissionService permissions = new RecordingPermissionService();
        permissions.fileRules = new ArrayList<>(List.of(existingRule("user.rule-1")));
        permissions.memoryRules = new ArrayList<>(List.of(existingRule("builtin.deny-sensitive-paths")));

        ScriptedReader reader = new ScriptedReader("r", "yes");
        ApprovalHandler h = handler(reader, permissions, false);

        ApprovalHandler.Verdict v = h.ask(op("WRITE", "src/Main.java"), "?", "HIGH", WS);
        assertEquals(ApprovalHandler.Choice.PERSISTED, v.choice());
        assertTrue(v.allowed());

        List<PermissionRule> saved = permissions.saved;
        assertNotNull(saved, "应触发一次 saveRules");
        assertEquals(3, saved.size(), "文件规则 + 内存规则 + 新规则，三者合并后不应丢任何一条");
        assertTrue(saved.stream().anyMatch(r -> "user.rule-1".equals(r.id())), "原有文件规则必须保留");
        assertTrue(saved.stream().anyMatch(r -> "builtin.deny-sensitive-paths".equals(r.id())),
                "内置安全基线必须保留（saveRules 是整体替换语义，不合并就会把基线抹掉）");

        PermissionRule added = saved.stream()
                .filter(r -> r.id() != null && r.id().startsWith("cli.allow-path-")).findFirst().orElseThrow();
        assertEquals(PermissionRule.RuleType.PATH, added.type());
        assertEquals(PermissionRule.RuleAction.ALLOW, added.action());
        assertEquals(PermissionRule.Anchor.ABS.code(), added.matcher().anchor());
        assertTrue(added.matcher().pattern().startsWith("regex:^"),
                "必须用锚定正则做精确匹配，不能用 glob —— glob 会把路径里的通配符当语法");
        assertTrue(added.matcher().pattern().endsWith("$"));
        assertTrue(added.matcher().pattern().contains("src/Main.java"));
    }

    @Test
    @DisplayName("r：二次确认不输入完整 yes 则不写入，回到选择且可改选 y")
    void persistRuleRequiresExplicitYes() {
        RecordingPermissionService permissions = new RecordingPermissionService();
        ScriptedReader reader = new ScriptedReader("r", "y", "y");
        ApprovalHandler h = handler(reader, permissions, false);

        ApprovalHandler.Verdict v = h.ask(op("WRITE", "a.txt"), "?", "HIGH", WS);
        assertNull(permissions.saved, "只输入 y（非 yes）不得写入规则文件");
        assertTrue(v.allowed(), "随后改选 y 仍可仅本次放行");
        assertEquals(ApprovalHandler.Choice.ALLOW_ONCE, v.choice());
    }

    @Test
    @DisplayName("路径归一必须镜像内核裁决器：前导斜杠一律剥离后再按工作空间根解析")
    void pathNormalizationMirrorsEvaluator() {
        RecordingPermissionService permissions = new RecordingPermissionService();
        ApprovalHandler h = handler(new ScriptedReader("r", "yes"), permissions, false);

        h.ask(op("WRITE", "/abs/x.txt"), "?", "HIGH", WS);

        String pattern = permissions.saved.get(permissions.saved.size() - 1).matcher().pattern();
        // 内核 PermissionEvaluator 会先去掉前导斜杠，再用 workspaceRoot.resolve(...) 求绝对路径；
        // 因此 "/abs/x.txt" 实际落在 <ws>/abs/x.txt。规则必须与之一致，否则永远匹配不上。
        assertTrue(pattern.contains("abs/x.txt"), "实际规则：" + pattern);
        assertFalse(pattern.contains("^/abs"), "不得把绝对路径原样当作工作空间外路径：" + pattern);
    }

    @Test
    @DisplayName("EXEC 操作生成 COMMAND 型规则（命令文本而非文件路径）")
    void execOpProducesCommandRule() {
        RecordingPermissionService permissions = new RecordingPermissionService();
        ApprovalHandler h = handler(new ScriptedReader("r", "yes"), permissions, false);

        h.ask(op("EXEC", "git push origin main"), "?", "HIGH", WS);

        PermissionRule added = permissions.saved.get(permissions.saved.size() - 1);
        assertEquals(PermissionRule.RuleType.COMMAND, added.type());
        assertTrue(added.matcher().pattern().contains("git push origin main"));
    }

    @Test
    @DisplayName("路径为空的 op 无法生成精确规则，不写入也不崩")
    void emptyPathCannotPersist() {
        RecordingPermissionService permissions = new RecordingPermissionService();
        ApprovalHandler h = handler(new ScriptedReader("r", "n"), permissions, false);

        Map<String, Object> empty = new HashMap<>();
        empty.put("opType", "WRITE");
        empty.put("path", "");
        ApprovalHandler.Verdict v = h.ask(empty, "?", "HIGH", WS);

        assertNull(permissions.saved);
        assertFalse(v.allowed());
    }

    // ------------------------------------------------------------------ 测试替身

    private static Map<String, Object> op(String opType, String path) {
        Map<String, Object> op = new HashMap<>();
        op.put("opType", opType);
        op.put("path", path);
        op.put("args", Map.of());
        return op;
    }

    private static ApprovalHandler handler(InputReader reader, PermissionService permissions,
                                           boolean autoApprove) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputSink out = OutputSink.of(new PrintStream(sink, true, StandardCharsets.UTF_8), Theme.plain());
        return new ApprovalHandler(out, reader, permissions, new StubWorkspaces(), autoApprove);
    }

    private static PermissionRule existingRule(String id) {
        return new PermissionRule().id(id).type(PermissionRule.RuleType.PATH)
                .matcher(new PermissionRule.Matcher("regex:^.*$", PermissionRule.Anchor.ABS.code()))
                .action(PermissionRule.RuleAction.ALLOW);
    }

    /** 脚本化输入：按顺序返回答案，并记录被问了多少次。 */
    private static final class ScriptedReader implements InputReader {

        private final Deque<String> answers;
        private int asked;

        ScriptedReader(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String readLine(String prompt) {
            asked++;
            return answers.isEmpty() ? null : answers.poll();
        }

        int asked() {
            return asked;
        }
    }

    /** 记录写入内容的权限服务替身。 */
    private static final class RecordingPermissionService implements PermissionService {

        private List<PermissionRule> fileRules = List.of();
        private List<PermissionRule> memoryRules = List.of();
        private List<PermissionRule> saved;

        @Override
        public PermissionDecision evaluateFileOp(FileOp op, String workspaceId) {
            return PermissionDecision.ASK;
        }

        @Override
        public PermissionDecision evaluateCommand(String command, String workspaceId) {
            return PermissionDecision.ASK;
        }

        @Override
        public List<PermissionRule> loadRules() {
            return new ArrayList<>(fileRules);
        }

        @Override
        public void saveRules(List<PermissionRule> rules) {
            this.saved = new ArrayList<>(rules);
        }

        @Override
        public List<PermissionRule> currentRules() {
            return new ArrayList<>(memoryRules);
        }

        @Override
        public void allowOnce(FileOp op) {
            // 本测试只关心 write 路径
        }
    }

    /** 工作空间配置替身：只提供物理根。 */
    private static final class StubWorkspaces implements WorkspaceConfig {

        @Override
        public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
            return Optional.of(PermissionLevel.FULL);
        }

        @Override
        public Optional<String> physicalPathOf(String workspaceId) {
            return WS.equals(workspaceId) ? Optional.of(WS_ROOT) : Optional.empty();
        }

        @Override
        public Optional<Workspace> getWorkspace(String workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<Workspace> listWorkspaces() {
            return List.of();
        }
    }
}
