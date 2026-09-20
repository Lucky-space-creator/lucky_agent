package com.lucky.agent.skill.repository.dto;

import java.util.List;

/**
 * Skill 元数据定义（落盘 {@code <frameworkRoot>/.skills/<id>/meta.json}，平台预置在
 * {@code <frameworkRoot>/.platform/skills/<id>/meta.json}）。
 *
 * <p>Skill 即"可被语义召回的 Tool/子 Agent"：{@code description}/{@code triggers} 供
 * 语义 Top-K 匹配（§6.3，非全量注入省 token）；{@code entry} 为可选的脚本命令模板，
 * 为空则为纯指令型（执行时返回 SKILL.md 正文引导模型按步骤操作）。</p>
 *
 * @param id         唯一标识（如 {@code pdf}），同时作为工具名前缀 {@code skill.<id>}
 * @param name       展示名（如「PDF 处理」）
 * @param description 功能描述（供语义匹配与模型理解）
 * @param triggers   语义触发词（关键词/意图）
 * @param type       类型：{@code TOOL} 或 {@code SUBAGENT}
 * @param sandbox    执行沙箱：true 时脚本执行需工作空间为全部权限
 * @param enabled    是否启用（默认 {@code true}）
 * @param deps       依赖的其他 Skill id 列表
 * @param entry      脚本命令模板（可含 {@code {args}} 占位符）；空为纯指令型
 * @param source     来源：{@code PLATFORM}（平台预置，只读）或 {@code USER}（用户自定义）
 * @param skillMd    渐进披露 Level 2 正文（SKILL.md 内容；表单保存时可选，列表/详情接口附带，
 *                   用于「新建/编辑」时回填正文编辑框，执行时仍以磁盘 SKILL.md 为准）
 */
public record SkillDef(
        String id,
        String name,
        String description,
        List<String> triggers,
        String type,
        boolean sandbox,
        boolean enabled,
        List<String> deps,
        String entry,
        String source,
        String skillMd,
        List<String> missingDeps) {

    public static final String TYPE_TOOL = "TOOL";
    public static final String TYPE_SUBAGENT = "SUBAGENT";
    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_USER = "USER";

    public SkillDef {
        if (type == null || type.isBlank()) {
            type = TYPE_TOOL;
        }
        if (source == null || source.isBlank()) {
            source = SOURCE_USER;
        }
        if (triggers == null) {
            triggers = List.of();
        }
        if (deps == null) {
            deps = List.of();
        }
        if (missingDeps == null) {
            missingDeps = List.of();
        }
    }

    /** 兼容旧 10 参构造（无正文/无缺失依赖标记）。 */
    public SkillDef(String id, String name, String description, List<String> triggers, String type,
                    boolean sandbox, boolean enabled, List<String> deps, String entry, String source) {
        this(id, name, description, triggers, type, sandbox, enabled, deps, entry, source, null, List.of());
    }

    /** 兼容旧 11 参构造（含正文、无缺失依赖标记）。 */
    public SkillDef(String id, String name, String description, List<String> triggers, String type,
                    boolean sandbox, boolean enabled, List<String> deps, String entry, String source, String skillMd) {
        this(id, name, description, triggers, type, sandbox, enabled, deps, entry, source, skillMd, List.of());
    }

    /** 变更启用状态（Skill 为不可变记录，返回新实例）。 */
    public SkillDef withEnabled(boolean enabled) {
        return new SkillDef(id, name, description, triggers, type, sandbox, enabled, deps, entry, source, skillMd, missingDeps);
    }

    /** 携带正文（加载/详情时回填）。 */
    public SkillDef withSkillMd(String md) {
        return new SkillDef(id, name, description, triggers, type, sandbox, enabled, deps, entry, source, md, missingDeps);
    }

    /** 携带缺失依赖标记（列表接口展示，E10）。 */
    public SkillDef withMissingDeps(List<String> missing) {
        return new SkillDef(id, name, description, triggers, type, sandbox, enabled, deps, entry, source, skillMd, missing);
    }

    /** 依赖缺失是否会导致无法注入/启用。 */
    public boolean hasMissingDeps() {
        return missingDeps != null && !missingDeps.isEmpty();
    }

    /** 平台预置 Skill 只读：不可卸载，仅可启停。 */
    public boolean platformReadOnly() {
        return SOURCE_PLATFORM.equals(source);
    }
}
