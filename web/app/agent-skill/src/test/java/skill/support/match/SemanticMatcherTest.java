package skill.support.match;

import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.repository.dto.SkillMatch;
import com.lucky.agent.skill.support.match.SemanticMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 语义匹配单元测试：Top-K 召回、触发词精确命中加权、空意图处理。
 */
class SemanticMatcherTest {

    private final SemanticMatcher matcher = new SemanticMatcher();

    private SkillDef skill(String id, String name, String desc, List<String> triggers) {
        return new SkillDef(id, name, desc, triggers, SkillDef.TYPE_TOOL, false, true,
                List.of(), null, SkillDef.SOURCE_USER);
    }

    @Test
    void testMatch_TopKOrdersByRelevance() {
        List<SkillDef> skills = List.of(
                skill("git", "Git 操作", "提交、分支、合并、回滚 git 仓库", List.of("git", "提交")),
                skill("pdf", "PDF 处理", "解析与生成 PDF 文档", List.of("pdf", "导出")),
                skill("image", "图像处理", "裁剪缩放图片", List.of("图片", "图像")));

        List<SkillMatch> matches = matcher.match(skills, "帮我解析一份 PDF 并导出", 3);

        assertNotNull(matches);
        assertTrue(matches.size() >= 1);
        assertEquals("pdf", matches.get(0).skill().id());
    }

    @Test
    void testMatch_TriggerExactHitBoostsScore() {
        // 触发词长度达标（>=4）命中时加权：不再直接给满分，而是叠加语义分（E12）
        List<SkillDef> skills = List.of(
                skill("pdf", "PDF 处理", "解析与生成 PDF 文档", List.of("pdf", "convert")),
                skill("git", "Git 操作", "代码版本管理", List.of("git", "提交")));

        List<SkillMatch> matches = matcher.match(skills, "convert 一份 PDF 文档", 5);

        assertTrue(matches.stream().anyMatch(m -> m.skill().id().equals("pdf")));
        double pdfScore = matches.stream()
                .filter(m -> m.skill().id().equals("pdf"))
                .mapToDouble(SkillMatch::score)
                .findFirst().orElse(0);
        // 触发词 convert 命中给 0.6 基础加权，最终分在 [0.6, 1.0) 区间，而非满分 1.0
        assertTrue(pdfScore >= 0.6 && pdfScore < 1.0, "期望触发词命中加权但不到满分，实际 " + pdfScore);
    }

    @Test
    void testMatch_ShortTriggerDoesNotBoomerang() {
        // 过短触发词（<4 字符）不再直接命中给满分，避免通用短词污染召回（E12）
        List<SkillDef> skills = List.of(
                skill("pdf", "PDF 处理", "解析与生成 PDF 文档", List.of("pdf")),
                skill("git", "Git 操作", "代码版本管理", List.of("git")));

        List<SkillMatch> matches = matcher.match(skills, "把报告导出成 PDF 文档", 5);

        assertNotNull(matches);
        // pdf 虽含短触发词 pdf，但分数不会因此冲到 1.0
        double pdfScore = matches.stream()
                .filter(m -> m.skill().id().equals("pdf"))
                .mapToDouble(SkillMatch::score)
                .findFirst().orElse(0);
        assertTrue(pdfScore < 1.0, "短触发词不应直接命中给满分");
    }

    @Test
    void testMatch_RespectsTopK() {
        List<SkillDef> skills = List.of(
                skill("a", "A", "数据库表结构分析", List.of("数据库")),
                skill("b", "B", "接口文档生成", List.of("接口")),
                skill("c", "C", "代码审查规范", List.of("审查")),
                skill("d", "D", "性能调优建议", List.of("性能")));

        List<SkillMatch> matches = matcher.match(skills, "分析数据库表结构并生成接口文档", 2);

        assertEquals(2, matches.size());
    }

    @Test
    void testMatch_BlankQueryReturnsEmpty() {
        List<SkillDef> skills = List.of(
                skill("pdf", "PDF 处理", "解析 PDF", List.of("pdf")));

        assertTrue(matcher.match(skills, "  ", 5).isEmpty());
        assertTrue(matcher.match(skills, null, 5).isEmpty());
    }
}
