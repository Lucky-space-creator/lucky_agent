package com.lucky.agent.skill.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.config.SkillProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Skill 磁盘加载器：扫描平台预置与用户自定义目录，读取技能定义与 SKILL.md 正文。
 *
 * <p>目录约定：</p>
 * <ul>
 *     <li>平台预置（只读）：{@code <frameworkRoot>/.platform/skills/<id>/}</li>
 *     <li>用户自定义：{@code <frameworkRoot>/.skills/<id>/}</li>
 * </ul>
 * <p>技能定义两路来源：优先目录内的 {@code meta.json}；缺失时回退解析 {@code SKILL.md}
 * 顶部的 YAML frontmatter（{@code name} / {@code description}），
 * 兼容社区标准技能包（Claude 等生态只有 SKILL.md，无 meta.json）。</p>
 * <p>SKILL.md 为渐进披露的 Level 2 正文：仅在 Skill 被命中并调用时按需读取（Level 1 元数据
 * 常驻内存作为工具描述，Level 3 引用资源执行时经执行臂按需访问），不启动即全量加载正文。</p>
 */
@Slf4j
public class SkillLoader {

    private final WorkspaceDirs dirs;
    private final SkillProperties properties;
    private final ObjectMapper objectMapper;

    public SkillLoader(WorkspaceDirs dirs, SkillProperties properties, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 平台预置 Skill 根目录（{@code <frameworkRoot>/.platform/skills}）。 */
    public Path platformSkillsRoot() {
        return dirs.platformDir().resolve("skills");
    }

    /** 用户自定义 Skill 根目录（{@code <frameworkRoot>/.skills}）。 */
    public Path userSkillsRoot() {
        return dirs.skillsDir();
    }

    /** 扫描全部 Skill 定义（平台 + 用户）。 */
    public List<SkillDef> loadAll() {
        List<SkillDef> defs = new ArrayList<>();
        defs.addAll(loadDir(platformSkillsRoot(), SkillDef.SOURCE_PLATFORM));
        defs.addAll(loadDir(userSkillsRoot(), SkillDef.SOURCE_USER));
        return defs;
    }

    /**
     * 从目录中的 {@code SKILL.md} frontmatter 构造技能定义（无 meta.json 的社区标准包）。
     * 供导入器复用：返回 null 表示 SKILL.md 缺失或缺少有效 description。
     */
    public SkillDef buildFromSkillMd(Path skillDir) {
        Path md = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return null;
        }
        Map<String, String> front;
        try {
            front = parseFrontMatter(Files.readString(md, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败：{}", md, e);
            return null;
        }
        String description = front.get("description");
        if (description == null || description.isBlank()) {
            log.warn("SKILL.md 缺少 description，已跳过：{}", md);
            return null;
        }
        String dirName = skillDir.getFileName().toString();
        String name = front.get("name") == null || front.get("name").isBlank() ? dirName : front.get("name").trim();
        return new SkillDef(dirName, name, description.trim(), List.of(),
                SkillDef.TYPE_TOOL, false, true, List.of(), "", SkillDef.SOURCE_USER);
    }

    /** 读取单个 Skill 的 SKILL.md 正文（Level 2 渐进披露，调用时按需加载）。 */
    public String readInstructions(String id) {
        Path dir = findSkillDir(id);
        if (dir == null) {
            return null;
        }
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return null;
        }
        try {
            return Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败：{}", md, e);
            return null;
        }
    }

    /** 定位某 Skill 所在目录（用户优先，其次平台）。 */
    public Path findSkillDir(String id) {
        Path user = userSkillsRoot().resolve(id);
        if (Files.isDirectory(user)) {
            return user;
        }
        Path platform = platformSkillsRoot().resolve(id);
        if (Files.isDirectory(platform)) {
            return platform;
        }
        return null;
    }

    private List<SkillDef> loadDir(Path root, String source) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .map(dir -> loadMeta(dir, source))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            log.warn("扫描 Skill 目录失败：{}", root, e);
            return List.of();
        }
    }

    /**
     * 读取技能定义：优先 {@code meta.json}；缺失时回退到社区标准
     * {@code SKILL.md} 的 YAML frontmatter，使无 meta.json 的技能包也能直接落盘使用。
     */
    private Optional<SkillDef> loadMeta(Path skillDir, String source) {
        Path meta = skillDir.resolve("meta.json");
        if (Files.isRegularFile(meta)) {
            try {
                SkillDef def = objectMapper.readValue(meta.toFile(), SkillDef.class);
                return Optional.of(withSource(skillDir, def, source));
            } catch (IOException e) {
                log.warn("解析 Skill meta 失败：{}", meta, e);
                return Optional.empty();
            }
        }
        return loadFromSkillMd(skillDir, source);
    }

    /**
     * 归一化来源与 id：{@code id} 一律取目录名。
     *
     * <p>{@link #findSkillDir(String)} 按 {@code userSkillsRoot.resolve(id)} 定位目录，
     * 若 id 与目录名不一致，{@link #readInstructions(String)} 会取不到正文、技能静默降级
     * （列表可见、可注入，但执行拿不到 SKILL.md）。因此无论 meta.json 里的 id 怎么写，
     * 加载后 id 统一为目录名，从根上消除「目录名与 id 不一致」的静默失效。</p>
     */
    private SkillDef withSource(Path skillDir, SkillDef def, String source) {
        String id = skillDir.getFileName().toString();
        return new SkillDef(id, def.name(), def.description(), def.triggers(),
                def.type(), def.sandbox(), def.enabled(), def.deps(), def.entry(), source);
    }

    /**
     * 从 {@code SKILL.md} 顶部的 YAML frontmatter 构造技能定义（兼容社区标准包）。
     *
     * <p><b>id 取目录名而非 frontmatter 的 name</b>：{@link #findSkillDir(String)} 是按
     * {@code userSkillsRoot.resolve(id)} 定位目录的，id 与目录名不一致会导致
     * {@link #readInstructions(String)} 取不到正文、技能静默降级。因此目录名作 id，
     * frontmatter 的 name 仅作展示名。</p>
     *
     * <p>triggers 留空：语义匹配会直接对 description 做 token 重叠打分，
     * 不引入停用词表反而避免通用词误召回。</p>
     */
    private Optional<SkillDef> loadFromSkillMd(Path skillDir, String source) {
        SkillDef def = buildFromSkillMd(skillDir);
        if (def == null) {
            return Optional.empty();
        }
        // 来源以目录为准（平台/用户），保持与 meta.json 路径一致
        return Optional.of(new SkillDef(def.id(), def.name(), def.description(), def.triggers(),
                def.type(), def.sandbox(), def.enabled(), def.deps(), def.entry(), source));
    }

    /**
     * 解析 {@code SKILL.md} 顶部的 YAML frontmatter。
     * 仅支持 {@code key: value} 单行形式（社区技能包的实际表达），零第三方依赖；
     * value 含冒号时按第一个冒号切分，保证 description 这类长文本完整。
     */
    private Map<String, String> parseFrontMatter(String text) {
        Map<String, String> front = new LinkedHashMap<>();
        if (text == null) {
            return front;
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---")) {
            return front;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return front;
        }
        for (String raw : text.substring(3, end).split("\\R")) {
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon > 0) {
                front.put(line.substring(0, colon).trim(), unquote(line.substring(colon + 1).trim()));
            }
        }
        return front;
    }

    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** meta 来源以所在目录为准（平台/用户目录是权威来源，覆盖文件内可能缺省/错误的 source）。 */
    private SkillDef withSource(SkillDef def, String source) {
        return new SkillDef(def.id(), def.name(), def.description(), def.triggers(),
                def.type(), def.sandbox(), def.enabled(), def.deps(), def.entry(), source);
    }
}
