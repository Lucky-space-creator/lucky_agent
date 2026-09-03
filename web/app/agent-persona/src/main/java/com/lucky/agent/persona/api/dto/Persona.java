package com.lucky.agent.persona.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 人格配置。
 *
 * @param id              人格 ID
 * @param name            显示名
 * @param systemPrompt    自定义 System Prompt（可选）
 * @param tone            语气（formal / casual）
 * @param verbosity       详尽度（0~1）
 * @param proactiveness   主动性（0~1）
 * @param preferredSkills 偏好 Skill 集（提升语义召回相关性）
 * @param preset          是否预设（预设不可编辑，随包内置）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Persona(
        String id,
        String name,
        String systemPrompt,
        String tone,
        double verbosity,
        double proactiveness,
        List<String> preferredSkills,
        boolean preset) {

    public static Persona preset(String id, String name, String tone, double verbosity, double proactiveness) {
        return new Persona(id, name, null, tone, verbosity, proactiveness, new ArrayList<>(), true);
    }

    public static Persona custom(String id, String name, String systemPrompt, String tone,
                                 double verbosity, double proactiveness, List<String> preferredSkills) {
        return new Persona(id, name, systemPrompt, tone, verbosity, proactiveness,
                preferredSkills == null ? new ArrayList<>() : preferredSkills, false);
    }
}
