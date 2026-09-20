package com.lucky.agent.persona.repository.dto;

/**
 * 行为参数（语气/详尽度/主动性），转为 Prompt 约束句注入推理上下文。
 *
 * @param tone           语气（formal / casual）
 * @param verbosity      详尽度（0~1）
 * @param proactiveness  主动性（0~1）
 */
public record BehaviorParam(String tone, double verbosity, double proactiveness) {

    public static BehaviorParam of(Persona persona) {
        return new BehaviorParam(persona.tone(), persona.verbosity(), persona.proactiveness());
    }
}
