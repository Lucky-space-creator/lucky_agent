package com.lucky.agent.persona.api;

import com.lucky.agent.persona.api.dto.Persona;

import java.util.List;

/**
 * 个性配置契约。
 *
 * <p>默认预设人格零配置可选；自定义 System Prompt 与行为参数存
 * {@code <frameworkRoot>/.config/persona.json}，运行时注入 core 引擎。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "persona")
public interface PersonaService {

    /** 当前激活人格（未配置回落预设默认）。 */
    Persona current();

    /** 预设人格列表（随包内置）。 */
    List<Persona> presets();

    /** 用户自定义人格列表。 */
    List<Persona> customPersonas();

    /** 保存自定义人格。 */
    Persona saveCustom(Persona persona);

    /** 激活指定人格（按 id 查预设或自定义）。 */
    Persona setActive(String personaId);

    /**
     * 渲染人格层 Prompt 片段（含自定义 systemPrompt 与行为参数约束句）。
     *
     * @param personaId 人格 ID
     * @return 注入引擎 System Prompt 的人格层片段
     */
    String renderPersonaLayer(String personaId);
}
