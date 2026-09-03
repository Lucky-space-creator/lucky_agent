package com.lucky.agent.core.models;

/**
 * 推理步骤单元（Thought / Action / Observation）。
 *
 * @param thought     思考
 * @param action      动作（工具名或 final）
 * @param actionArgs  动作参数
 * @param observation 观察结果
 */
public record Step(String thought, String action, Object actionArgs, String observation) {

    public static Step of(String thought, String action, Object actionArgs, String observation) {
        return new Step(thought, action, actionArgs, observation);
    }
}
