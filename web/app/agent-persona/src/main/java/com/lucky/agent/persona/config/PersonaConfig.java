package com.lucky.agent.persona.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.persona.api.BehaviorEngine;
import com.lucky.agent.persona.behavior.BehaviorParamEngine;
import com.lucky.agent.persona.bind.PersonaSkillBinder;
import com.lucky.agent.persona.center.PersonaStore;
import com.lucky.agent.persona.collab.MultiAgentOrchestrator;
import com.lucky.agent.persona.api.PersonaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-persona 配置。
 */
@Configuration
public class PersonaConfig {

    /** 人格存储（persona.json）。 */
    @Bean
    public PersonaStore personaStore(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        return new PersonaStore(dirs, objectMapper);
    }

    /** 行为参数引擎。 */
    @Bean
    public BehaviorEngine behaviorEngine() {
        return new BehaviorParamEngine();
    }

    /** 多 Agent 角色编排（执行委托 core 子代理执行器）。 */
    @Bean
    public MultiAgentOrchestrator multiAgentOrchestrator(PersonaService personaService) {
        return new MultiAgentOrchestrator(personaService);
    }

    /** 个性-Skill 绑定。 */
    @Bean
    public PersonaSkillBinder personaSkillBinder(PersonaService personaService) {
        return new PersonaSkillBinder(personaService);
    }
}
