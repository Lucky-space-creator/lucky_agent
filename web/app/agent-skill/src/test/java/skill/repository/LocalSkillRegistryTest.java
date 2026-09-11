package skill.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.skill.repository.LocalSkillRegistry;
import com.lucky.agent.skill.repository.SkillLoader;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.config.SkillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Skill 注册中心单元测试：平台/用户两路加载、启停持久化、热插拔重载、只读保护。
 */
class LocalSkillRegistryTest {

    @TempDir
    Path temp;

    private WorkspaceDirs dirs;
    private LocalSkillRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        dirs = new WorkspaceDirs();
        setField(dirs, "frameworkRoot", temp.resolve("fw").toString());
        setField(dirs, "workspaceRoot", temp.resolve("ws").toString());
        dirs.init();
        ObjectMapper mapper = new ObjectMapper();
        SkillProperties props = new SkillProperties(5, "skills", "platform", false, 30000);
        registry = new LocalSkillRegistry(new SkillLoader(dirs, props, mapper), dirs, mapper, props);
        registry.init();
    }

    @Test
    void testLoadsPlatformAndUserSkills() throws Exception {
        writeMeta("fw/platform/skills/git/meta.json", """
                {"id":"git","name":"Git 操作","description":"提交与分支管理","triggers":["git","提交"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """);
        writeMeta("fw/skills/pdf/meta.json", """
                {"id":"pdf","name":"PDF 处理","description":"解析 PDF","triggers":["pdf"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """);

        registry.reload();

        assertEquals(2, registry.list().size());
        assertTrue(registry.find("git").isPresent());
        assertEquals(SkillDef.SOURCE_PLATFORM, registry.find("git").get().source());
        assertEquals(SkillDef.SOURCE_USER, registry.find("pdf").get().source());
        assertTrue(registry.isReadOnly("git"));
        assertFalse(registry.isReadOnly("pdf"));
    }

    @Test
    void testTogglePersistsEnabledState() throws Exception {
        writeMeta("fw/skills/pdf/meta.json", """
                {"id":"pdf","name":"PDF 处理","description":"解析 PDF","triggers":["pdf"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """);
        registry.reload();

        registry.setEnabled("pdf", false);
        assertFalse(registry.find("pdf").get().enabled());

        // 重新加载后状态保持（持久化生效）
        registry.reload();
        assertFalse(registry.find("pdf").get().enabled());
        assertTrue(registry.listEnabled().isEmpty());
    }

    @Test
    void testPlatformSkillCannotBeRemoved() throws Exception {
        writeMeta("fw/platform/skills/git/meta.json", """
                {"id":"git","name":"Git 操作","description":"提交","triggers":["git"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """);
        registry.reload();

        assertFalse(registry.remove("git"));
        assertTrue(registry.find("git").isPresent());
    }

    @Test
    void testUserSkillSaveThenRemove() {
        SkillDef def = new SkillDef("custom", "自定义", "自定义技能", List.of("自定义"),
                SkillDef.TYPE_TOOL, false, true, List.of(), "", SkillDef.SOURCE_USER);

        SkillDef saved = registry.save(def);
        assertEquals("custom", saved.id());
        assertTrue(registry.find("custom").isPresent());

        assertTrue(registry.remove("custom"));
        assertFalse(registry.find("custom").isPresent());
    }

    @Test
    void testMissingSkillReturnsEmpty() {
        assertEquals(Optional.empty(), registry.find("not-exist"));
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void testImportFromPackageCopiesFilesAndForcesUserSource() throws Exception {
        Path pkg = temp.resolve("pkg");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("meta.json"), """
                {"id":"imported","name":"导入技能","description":"本地上传","triggers":["导入"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":"","source":"PLATFORM"}
                """, StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("SKILL.md"), "# 导入技能正文", StandardCharsets.UTF_8);

        SkillDef def = registry.importFrom(pkg);

        // id 强制取目录名（E6），meta.json 里的 id 只作展示名来源，不再作为目录键
        assertEquals("pkg", def.id());
        assertEquals(SkillDef.SOURCE_USER, def.source());
        assertTrue(registry.find("pkg").isPresent());
        assertEquals("# 导入技能正文",
                Files.readString(temp.resolve("fw/skills/pkg/SKILL.md"), StandardCharsets.UTF_8));
    }

    @Test
    void testImportFromRejectsPlatformReadOnly() throws Exception {
        writeMeta("fw/platform/skills/git/meta.json", """
                {"id":"git","name":"Git 操作","description":"提交","triggers":["git"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """);
        registry.reload();
        // 目录名即 id（E6）：要覆盖平台只读的 git，用户包目录名必须也是 git
        Path pkg = temp.resolve("git");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("meta.json"), """
                {"id":"git","name":"覆盖尝试","description":"覆盖平台","triggers":["git"],
                 "type":"TOOL","sandbox":false,"enabled":true,"deps":[],"entry":""}
                """, StandardCharsets.UTF_8);

        assertThrows(AgentException.class, () -> registry.importFrom(pkg));
        assertEquals("Git 操作", registry.find("git").get().name());
    }

    @Test
    void testImportFromMissingMetaThrows() throws Exception {
        Path pkg = temp.resolve("pkg-empty");
        Files.createDirectories(pkg);

        assertThrows(AgentException.class, () -> registry.importFrom(pkg));
    }

    private void writeMeta(String relative, String json) throws Exception {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private void setField(Object target, String name, String value) throws Exception {
        Field field = WorkspaceDirs.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
