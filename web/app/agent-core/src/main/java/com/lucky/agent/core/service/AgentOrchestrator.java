package com.lucky.agent.core.service;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;

/**
 * 核心 Agent 编排器统一契约。
 *
 * <p>项目同时存在两套核心编排实现，经 {@code core.orchestrator-mode}（见 application.yml）自由切换：
 * <ul>
 *   <li>{@code reactor}（默认）：{@link Orchestrator}，基于 REACT 主回环 + Reactor 事件流的编排实现；</li>
 *   <li>{@code langgraph}：{@link LangGraphOrchestrator}，基于 LangGraph4j 状态图（DAG）的编排实现。</li>
 * </ul>
 * 两者语义等价（均实现「分析 → 拆分 → 逐任务执行 → 客观验证 → 达成度判定 → 记忆管理 → 再分析」主回环），
 * 仅在底层调度形态上不同。会话层 {@code ConversationManager} 只依赖本接口，不感知具体实现。</p>
 */
public interface AgentOrchestrator {

    /**
     * 运行一次编排（复杂/简单任务统一入口）。
     *
     * @param ref       会话引用
     * @param ctx       会话上下文（goal 为原始目标）
     * @param publisher 事件发布器
     * @return 最终执行结果（总结文本）
     */
    EngineRunResult run(SessionRef ref, ConversationCtx ctx, AgentEventPublisher publisher);
}
