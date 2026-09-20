package com.lucky.agent.skill.service;

import com.lucky.agent.skill.repository.dto.SkillDef;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Skill 注册中心契约：生命周期管理 + 启用状态 + 热插拔重载。
 *
 * <p>平台预置（随包只读，可启停不可卸载）与用户自定义（写入 {@code <frameworkRoot>/.skills/}）
 * 两路加载；启用状态持久化在 {@code <frameworkRoot>/.config/skill-state.json}，重启不丢。</p>
 */
public interface SkillRegistry {

    /** 全部 Skill（含禁用）。 */
    List<SkillDef> list();

    /** 启用的 Skill。 */
    List<SkillDef> listEnabled();

    /** 按 id 查询。 */
    Optional<SkillDef> find(String id);

    /** 启用/禁用某 Skill（平台预置仅改启用状态，文件不落盘平台目录）。 */
    void setEnabled(String id, boolean enabled);

    /** 平台预置 Skill 不可卸载。 */
    boolean isReadOnly(String id);

    /** 保存用户自定义 Skill（写入 meta.json；新增或覆盖）。 */
    SkillDef save(SkillDef def);

    /**
     * 从本地包目录导入 Skill（本地上传 / 目录导入）：包内须含 {@code meta.json} 或 {@code SKILL.md}，
     * 整个目录拷贝进 {@code skills/<id>/} 并重载；平台预置 Skill 拒绝覆盖。
     */
    SkillDef importFrom(Path packageDir);

    /** 从本机目录直接导入 Skill（目录名即 id），语义与 {@link #importFrom} 一致。 */
    SkillDef importFromDir(Path sourceDir);

    /** 删除用户自定义 Skill（平台预置返回 false）。 */
    boolean remove(String id);

    /** 重新扫描目录加载（热插拔：新增/删除/改动后调用即生效，无需重启）。 */
    void reload();

    /** 注册表变更版本号（每次加载/启停/增删自增，供前端轮询感知）。 */
    long revision();
}
