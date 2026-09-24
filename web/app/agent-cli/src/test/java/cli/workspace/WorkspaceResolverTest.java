package cli.workspace;

import com.lucky.agent.cli.workspace.WorkspaceResolver;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作空间解析。
 *
 * <p>两个必须守住的行为：<b>自动挑选要排除内置工作空间</b>（内置的物理路径在框架根内，
 * 紧邻 config/ 与 memory/，误落会让 Agent 在框架自己的数据目录里干活）；
 * <b>多候选不猜</b>（猜错的代价是用户在毫不知情的情况下让 Agent 改错目录）。</p>
 */
class WorkspaceResolverTest {

    @Test
    @DisplayName("自动挑选优先「非内置且可写」")
    void prefersNonBuiltinWritable() {
        Workspace builtin = ws("b", "默认", "/fw/workspace", PermissionLevel.FULL, true);
        Workspace readonly = ws("r", "只读", "/data/ro", PermissionLevel.READ_ONLY, false);

        WorkspaceResolver.Result r = WorkspaceResolver.resolve(config(builtin, readonly), null);
        assertEquals(WorkspaceResolver.Status.OK, r.status());
        assertEquals("r", r.workspace().workspaceId(), "只有这一个非内置候选，应选中它（即便只读）");
    }

    @Test
    @DisplayName("自动挑选排除内置：即使内置权限更高")
    void excludesBuiltin() {
        Workspace builtin = ws("b", "默认", "/fw/workspace", PermissionLevel.FULL, true);
        Workspace custom = ws("c", "项目", "/data/proj", PermissionLevel.MODIFY, false);

        assertEquals("c", WorkspaceResolver.resolve(config(builtin, custom), null).workspace().workspaceId());
    }

    @Test
    @DisplayName("仅存在内置工作空间时兜底可用（调用方必须回显提醒）")
    void fallsBackToBuiltin() {
        Workspace builtin = ws("b", "默认", "/fw/workspace", PermissionLevel.MODIFY, true);
        WorkspaceResolver.Result r = WorkspaceResolver.resolve(config(builtin), null);
        assertEquals(WorkspaceResolver.Status.OK, r.status());
        assertTrue(r.workspace().builtin());
    }

    @Test
    @DisplayName("多个同等候选 → AMBIGUOUS，不自行挑一个")
    void ambiguousInsteadOfGuessing() {
        Workspace a = ws("a", "甲", "/data/a", PermissionLevel.MODIFY, false);
        Workspace b = ws("b", "乙", "/data/b", PermissionLevel.MODIFY, false);

        WorkspaceResolver.Result r = WorkspaceResolver.resolve(config(a, b), null);
        assertEquals(WorkspaceResolver.Status.AMBIGUOUS, r.status());
        assertFalse(r.ok());
        assertEquals(2, r.candidates().size());
    }

    @Test
    @DisplayName("没有任何工作空间 → EMPTY")
    void empty() {
        assertEquals(WorkspaceResolver.Status.EMPTY,
                WorkspaceResolver.resolve(config(), null).status());
    }

    @Test
    @DisplayName("显式选择：ID 精确 > 名称精确 > 名称包含 > 路径归一相等")
    void explicitSelectorMatching() {
        Workspace a = ws("id-aaa", "前端项目", "/data/web", PermissionLevel.MODIFY, false);
        Workspace b = ws("id-bbb", "后端项目", "/data/api", PermissionLevel.MODIFY, false);
        WorkspaceConfig cfg = config(a, b);

        assertEquals("id-aaa", WorkspaceResolver.resolve(cfg, "id-aaa").workspace().workspaceId());
        assertEquals("id-bbb", WorkspaceResolver.resolve(cfg, "后端项目").workspace().workspaceId());
        assertEquals("id-aaa", WorkspaceResolver.resolve(cfg, "前端").workspace().workspaceId());
        assertEquals("id-bbb", WorkspaceResolver.resolve(cfg, "/data/api").workspace().workspaceId());
    }

    @Test
    @DisplayName("选择器无匹配 → NOT_FOUND（不用空结果冒充成功）")
    void selectorNotFound() {
        Workspace a = ws("id-aaa", "前端项目", "/data/web", PermissionLevel.MODIFY, false);
        assertEquals(WorkspaceResolver.Status.NOT_FOUND,
                WorkspaceResolver.resolve(config(a), "不存在的名字").status());
    }

    @Test
    @DisplayName("包含匹配命中多个 → AMBIGUOUS 并列出候选")
    void fuzzySelectorAmbiguous() {
        Workspace a = ws("id-aaa", "项目甲", "/data/a", PermissionLevel.MODIFY, false);
        Workspace b = ws("id-bbb", "项目乙", "/data/b", PermissionLevel.MODIFY, false);

        WorkspaceResolver.Result r = WorkspaceResolver.resolve(config(a, b), "项目");
        assertEquals(WorkspaceResolver.Status.AMBIGUOUS, r.status());
        assertEquals(2, r.candidates().size());
    }

    @Test
    @DisplayName("摘要里必须同时出现名称/权限/路径与内置标记（用户要能一眼确认落点）")
    void describeContainsKeyFacts() {
        String text = WorkspaceResolver.describe(
                ws("id-abc12345", "项目", "/data/proj", PermissionLevel.READ_ONLY, true));
        assertTrue(text.contains("项目"));
        assertTrue(text.contains("只读"));
        assertTrue(text.contains("/data/proj"));
        assertTrue(text.contains("内置"));
        assertTrue(text.contains("id-abc12"), "应给出短 ID 便于对照：" + text);
    }

    @Test
    @DisplayName("权限缺失时摘要按默认级别展示，不出现 null")
    void describeDefaultsPermission() {
        Workspace w = new Workspace().workspaceId("x").name("n").path("/p");
        String text = WorkspaceResolver.describe(w);
        assertFalse(text.contains("null"), text);
    }

    // ------------------------------------------------------------------ 辅助

    private static Workspace ws(String id, String name, String path, PermissionLevel level,
                                boolean builtin) {
        return new Workspace().workspaceId(id).name(name).path(path)
                .permissionLevel(level).builtin(builtin);
    }

    private static WorkspaceConfig config(Workspace... list) {
        List<Workspace> all = List.of(list);
        return new WorkspaceConfig() {
            @Override
            public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
                return all.stream().filter(w -> w.workspaceId().equals(workspaceId))
                        .map(Workspace::permissionLevel).findFirst();
            }

            @Override
            public Optional<String> physicalPathOf(String workspaceId) {
                return all.stream().filter(w -> w.workspaceId().equals(workspaceId))
                        .map(Workspace::path).findFirst();
            }

            @Override
            public Optional<Workspace> getWorkspace(String workspaceId) {
                return all.stream().filter(w -> w.workspaceId().equals(workspaceId)).findFirst();
            }

            @Override
            public List<Workspace> listWorkspaces() {
                return all;
            }
        };
    }
}
