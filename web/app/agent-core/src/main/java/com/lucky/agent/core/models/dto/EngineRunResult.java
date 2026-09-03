package com.lucky.agent.core.models.dto;

import com.lucky.agent.common.constant.Phase;

/**
 * 引擎运行结果。
 *
 * @param sessionId 会话 ID
 * @param phase     运行阶段
 * @param finalText 最终回答文本
 * @param tokenUsed 本轮 token 用量
 * @param model     模型名
 * @param status    状态
 * @param error     错误信息（可选）
 */
public record EngineRunResult(
        String sessionId,
        Phase phase,
        String finalText,
        long tokenUsed,
        String model,
        String status,
        String error) {

    public static EngineRunResult of(String sessionId, Phase phase, String finalText,
                                     long tokenUsed, String model, String status) {
        return new EngineRunResult(sessionId, phase, finalText, tokenUsed, model, status, null);
    }

    public static EngineRunResult error(String sessionId, Phase phase, String error) {
        return new EngineRunResult(sessionId, phase, null, 0, null, "error", error);
    }
}
