package com.lucky.agent.skill.api;

import com.lucky.agent.skill.api.dto.SkillDef;
import com.lucky.agent.skill.api.dto.SkillMatch;

import java.util.List;

/**
 * Skill 语义匹配契约：对用户意图/任务目标与 Skill 描述/触发词做相似度比对，召回 Top-K。
 *
 * <p>只注入命中的 Skill（非全量），避免全量 Tool 塞爆上下文（§6.3 核心降本点）。
 * 匹配策略可换实现（向量/关键词），默认本机关键词 + 中文 bigram（与 memory 一致，零依赖）。</p>
 */
public interface SkillMatcher {

    /**
     * 从给定候选 Skill 中召回与查询最相关的 Top-K。
     *
     * @param skills 候选 Skill（通常为启用的全部）
     * @param query  用户意图/任务目标文本
     * @param topK   召回上限
     * @return 按相关度降序的命中列表
     */
    List<SkillMatch> match(List<SkillDef> skills, String query, int topK);
}
