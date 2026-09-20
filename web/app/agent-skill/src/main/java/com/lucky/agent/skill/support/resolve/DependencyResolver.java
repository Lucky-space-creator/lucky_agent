package com.lucky.agent.skill.support.resolve;

import com.lucky.agent.skill.repository.dto.SkillDef;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 依赖解析器：校验某 Skill 声明的 deps 是否都被注册中心持有。
 *
 * <p>依赖缺失的 Skill 不注入模型（避免调用时失效），并在列表中标记提示用户补装。</p>
 */
public class DependencyResolver {

    /** 返回缺失的依赖 Skill id 列表（空表示依赖齐全）。 */
    public List<String> missingDeps(SkillDef skill, List<SkillDef> all) {
        if (skill == null || skill.deps() == null || skill.deps().isEmpty()) {
            return List.of();
        }
        Set<String> available = all.stream().map(SkillDef::id).collect(Collectors.toSet());
        return skill.deps().stream().filter(dep -> !available.contains(dep)).toList();
    }

    /** 依赖是否全部可解析。 */
    public boolean resolve(SkillDef skill, List<SkillDef> all) {
        return missingDeps(skill, all).isEmpty();
    }
}
