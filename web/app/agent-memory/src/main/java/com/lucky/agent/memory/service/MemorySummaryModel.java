package com.lucky.agent.memory.service;

/**
 * 记忆总结模型能力（分层 Markdown 记忆的 LLM 总结入口）。
 *
 * <p>本模块不依赖具体模型框架：由上层（agent-core）基于 {@code ModelRouter} 提供实现，
 * 路由到「记忆管理 Agent」端点（role=memory），未配置时回退主力模型。</p>
 */
@FunctionalInterface
public interface MemorySummaryModel {

    /**
     * 调用 LLM 生成/合并一段记忆总结。
     *
     * @param instruction 总结/合并指令（中文，描述期望输出形态）
     * @param content     待总结内容（原始聊天记录，或「旧记忆 + 新增增量」）
     * @return 总结文本（Markdown 格式）；失败抛异常由调用方兜底降级
     */
    String summarize(String instruction, String content);
}
