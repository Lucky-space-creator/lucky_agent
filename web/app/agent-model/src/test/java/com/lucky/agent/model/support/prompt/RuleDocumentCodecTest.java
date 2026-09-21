package com.lucky.agent.model.support.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则文档编解码测试：覆盖分节解析、控制标记、往返幂等与旧文件兼容。
 */
class RuleDocumentCodecTest {

    @Test
    @DisplayName("解析：## 分节 → 多条规则，节标题为规则名，节正文为内容")
    void parseSections() {
        String raw = """
                <!-- 前言说明 -->

                ## 提交规范

                一次提交只做一件事。

                ## 编码风格

                缩进 4 空格。
                """;
        List<RuleItem> items = RuleDocumentCodec.parse(raw);
        assertEquals(2, items.size());
        assertEquals("提交规范", items.get(0).name());
        assertEquals("一次提交只做一件事。", items.get(0).content());
        assertTrue(items.get(0).enabled());
        assertEquals(RuleItem.SCOPE_GLOBAL, items.get(0).scope());
        assertEquals("编码风格", items.get(1).name());
    }

    @Test
    @DisplayName("控制标记：[P] 项目作用域、[ ] 停用，可组合且顺序无关")
    void parseControlMarks() {
        String raw = """
                ## [P] 项目编码规范

                项目级规则。

                ## [ ] 临时实验

                全局但停用。

                ## [P][ ] 待定规则

                项目且停用。

                ## [ ][P] 逆序标记

                标记顺序无关。
                """;
        List<RuleItem> items = RuleDocumentCodec.parse(raw);
        assertEquals(4, items.size());

        assertTrue(items.get(0).isProject());
        assertTrue(items.get(0).enabled());
        assertEquals("项目编码规范", items.get(0).name());

        assertFalse(items.get(1).isProject());
        assertFalse(items.get(1).enabled());
        assertEquals("临时实验", items.get(1).name());

        assertTrue(items.get(2).isProject());
        assertFalse(items.get(2).enabled());
        assertEquals("待定规则", items.get(2).name());

        assertTrue(items.get(3).isProject());
        assertFalse(items.get(3).enabled());
        assertEquals("逆序标记", items.get(3).name());
    }

    @Test
    @DisplayName("往返幂等：parse(render(items)) 与 items 等价")
    void roundTrip() {
        List<RuleItem> items = List.of(
                new RuleItem("全局规则A", "内容A\n第二行", true, RuleItem.SCOPE_GLOBAL),
                new RuleItem("项目规则B", "内容B", true, RuleItem.SCOPE_PROJECT),
                new RuleItem("停用规则C", "内容C", false, RuleItem.SCOPE_GLOBAL));
        String rendered = RuleDocumentCodec.render("<!-- header -->", items);
        List<RuleItem> parsed = RuleDocumentCodec.parse(rendered);

        assertEquals(items.size(), parsed.size());
        for (int i = 0; i < items.size(); i++) {
            assertEquals(items.get(i).name(), parsed.get(i).name(), "第 " + i + " 条名称");
            assertEquals(items.get(i).content(), parsed.get(i).content(), "第 " + i + " 条内容");
            assertEquals(items.get(i).enabled(), parsed.get(i).enabled(), "第 " + i + " 条启用态");
            assertEquals(items.get(i).scope(), parsed.get(i).scope(), "第 " + i + " 条作用域");
        }
    }

    @Test
    @DisplayName("旧文件兼容：无 ## 分节时 isStructured 为 false，前缀保留")
    void legacyFileCompatibility() {
        String legacy = "你是助手，禁止外传数据。\n\n## 注意\n\n这里其实是正文内的二级标题写法";
        // 含 ## 分节 → 视为结构化
        assertTrue(RuleDocumentCodec.isStructured(legacy));
        assertFalse(RuleDocumentCodec.isStructured("纯段落，没有任何分节。"));
        assertFalse(RuleDocumentCodec.isStructured(""));
        assertFalse(RuleDocumentCodec.isStructured(null));
    }

    @Test
    @DisplayName("extractHeader 仅取首个分节之前的内容，渲染时回填")
    void extractHeader() {
        String raw = """
                <!-- 我是前言 -->
                第二行前言
                ## 规则一

                正文
                """;
        String header = RuleDocumentCodec.extractHeader(raw);
        assertTrue(header.contains("我是前言"));
        assertTrue(header.contains("第二行前言"));
        assertFalse(header.contains("规则一"));

        String rendered = RuleDocumentCodec.render(header, List.of(RuleItem.of("规则一", "正文")));
        assertTrue(rendered.startsWith("<!-- 我是前言 -->"));
    }

    @Test
    @DisplayName("空规则过滤：无名称无正文的分节不产出规则项")
    void emptySectionFiltered() {
        List<RuleItem> items = RuleDocumentCodec.parse("##  \n\n\n## 有用\n\n内容");
        assertEquals(1, items.size());
        assertEquals("有用", items.get(0).name());
    }

    @Test
    @DisplayName("### 不视为规则边界，正文内可安全使用三级标题")
    void tripleHashNotBoundary() {
        String raw = """
                ## 外层规则

                ### 子标题

                子标题内容
                """;
        List<RuleItem> items = RuleDocumentCodec.parse(raw);
        assertEquals(1, items.size());
        assertTrue(items.get(0).content().contains("### 子标题"));
    }

    @Test
    @DisplayName("RuleStore：单文件分节保存后可按同一顺序读回（含文件头保留）")
    void storeSaveAndList(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        RuleStore store = new RuleStore(new FakeDirs(tmp));
        Path globalFile = store.globalFile();
        Files.createDirectories(globalFile.getParent());
        Files.writeString(globalFile, """
                <!-- 我的前言 -->

                ## 规则甲

                甲内容

                ## [P] 规则乙

                乙内容
                """);

        List<RuleItem> items = store.listGlobal();
        assertEquals(2, items.size());
        assertFalse(items.get(0).isProject());
        assertTrue(items.get(1).isProject());

        // 覆盖保存：仅保留一条，且文件头仍在
        store.save(null, RuleItem.SCOPE_GLOBAL, List.of(RuleItem.of("仅此一条", "内容")));
        List<RuleItem> after = store.listGlobal();
        assertEquals(1, after.size());
        assertEquals("仅此一条", after.get(0).name());
        assertTrue(Files.readString(globalFile).contains("我的前言"));
    }

    @Test
    @DisplayName("RuleStore：旧版未分节 LUCKY.md 兼容为单条「全局规则」")
    void storeLegacyFileCompat(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        RuleStore store = new RuleStore(new FakeDirs(tmp));
        Path f = store.globalFile();
        Files.createDirectories(f.getParent());
        Files.writeString(f, "纯段落规则：所有文件操作限制在工作空间内。");

        List<RuleItem> items = store.listGlobal();
        assertEquals(1, items.size());
        assertEquals("全局规则", items.get(0).name());
        assertTrue(items.get(0).content().contains("工作空间内"));
    }

    @Test
    @DisplayName("RuleStore：多文件规则目录回退读取，文件名即规则名")
    void storeRulesDirFallback(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        RuleStore store = new RuleStore(new FakeDirs(tmp));
        Path dir = store.globalRulesDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("提交规范.md"), "一次提交只做一件事。");
        Files.writeString(dir.resolve("测试要求.md"), "新功能必须带测试。");
        Files.writeString(dir.resolve("忽略我.txt"), "非 md 不读");

        List<RuleItem> items = store.listGlobal();
        assertEquals(2, items.size());
        assertEquals("提交规范", items.get(0).name());
        assertEquals("测试要求", items.get(1).name());
    }

    @Test
    @DisplayName("RuleStore：注入文本仅含启用项，全局在前项目在后")
    void storeRenderForPrompt(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        RuleStore store = new RuleStore(new FakeDirs(tmp));
        Path global = store.globalFile();
        Files.createDirectories(global.getParent());
        Files.writeString(global, """
                ## 全局甲

                甲

                ## [ ] 全局停用

                不该出现
                """);

        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("LUCKY.md"), """
                ## [P] 项目乙

                乙
                """);

        String text = store.renderForPrompt(ws.toString());
        assertTrue(text.contains("【全局规则】"));
        assertTrue(text.contains("### 全局甲"));
        assertTrue(text.contains("【项目规则】"));
        assertTrue(text.contains("### 项目乙"));
        assertFalse(text.contains("不该出现"));
        // 全局在前、项目在后
        assertTrue(text.indexOf("全局甲") < text.indexOf("项目乙"));
    }

    /** 测试用最小 WorkspaceDirs 替身：仅重写 frameworkRoot。 */
    private static final class FakeDirs extends com.lucky.agent.common.constant.WorkspaceDirs {
        private final Path root;

        private FakeDirs(Path root) {
            this.root = root;
        }

        @Override
        public Path frameworkRoot() {
            return root;
        }
    }
}
