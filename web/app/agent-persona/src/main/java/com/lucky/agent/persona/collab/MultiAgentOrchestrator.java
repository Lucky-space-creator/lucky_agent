package com.lucky.agent.persona.collab;

import com.lucky.agent.persona.api.PersonaService;
import com.lucky.agent.persona.api.dto.Persona;

import java.util.Optional;

/**
 * 多 Agent 协作角色编排。
 *
 * <p>按子代理角色准备人格与偏好 Skill；实际子代理创建、执行与结果聚合由 core 的
 * SubAgentExecutor 完成，本类不持有任务调度与结果聚合逻辑，避免与 core 重复。</p>
 */
public class MultiAgentOrchestrator {

    private final PersonaService personaService;

    public MultiAgentOrchestrator(PersonaService personaService) {
        this.personaService = personaService;
    }

    /**
     * 按角色取人格（按名称匹配预设/自定义，未匹配回落当前人格）。
     *
     * @param role 角色名（如 code-reviewer）
     * @return 人格
     */
    public Persona personaForRole(String role) {
        if (role == null || role.isBlank()) {
            return personaService.current();
        }
        Optional<Persona> match = personaService.presets().stream()
                .filter(p -> role.equalsIgnoreCase(p.id()) || role.equalsIgnoreCase(p.name()))
                .findFirst();
        return match.orElseGet(personaService::current);
    }
}
