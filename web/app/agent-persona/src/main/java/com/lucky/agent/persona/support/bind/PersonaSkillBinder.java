package com.lucky.agent.persona.support.bind;

import com.lucky.agent.persona.service.PersonaService;
import com.lucky.agent.persona.repository.dto.Persona;

import java.util.List;

/**
 * 个性-Skill 绑定：特定人格附加偏好 Skill 集，提升语义召回相关性。
 */
public class PersonaSkillBinder {

    private final PersonaService personaService;

    public PersonaSkillBinder(PersonaService personaService) {
        this.personaService = personaService;
    }

    /**
     * 取人格偏好 Skill 集。
     *
     * @param personaId 人格 ID
     * @return 偏好 Skill 列表
     */
    public List<String> preferredSkills(String personaId) {
        Persona persona = personaService.presets().stream()
                .filter(p -> p.id().equals(personaId))
                .findFirst()
                .orElseGet(personaService::current);
        return persona.preferredSkills() == null ? List.of() : persona.preferredSkills();
    }
}
