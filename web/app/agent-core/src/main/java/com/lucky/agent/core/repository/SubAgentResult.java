package com.lucky.agent.core.repository;

/**
 * 子代理执行结果。
 *
 * @param subAgentId 子代理 ID
 * @param summary    摘要（summaryOnly 模式下只回摘要）
 * @param success    是否成功
 */
public record SubAgentResult(String subAgentId, String summary, boolean success) {
}
