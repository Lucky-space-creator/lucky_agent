package com.lucky.agent.skill.api.dto;

/**
 * Skill 语义匹配命中项。
 *
 * @param skill 命中的 Skill 定义
 * @param score 匹配得分（0~1，越高越相关）
 */
public record SkillMatch(SkillDef skill, double score) {
}
