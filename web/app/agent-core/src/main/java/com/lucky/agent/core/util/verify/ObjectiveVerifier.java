package com.lucky.agent.core.util.verify;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Plan;

/**
 * 客观验证器契约（流程图 D / I 节点）。
 *
 * <p>验证器对「某个计划步骤」做一次校验并返回 {@link VerificationResult}。
 * 执行顺序与聚合策略由 {@link VerificationChain} 统一裁决，验证器自身不做最终决策。</p>
 *
 * <p>所有验证器必须遵守：失败以结果返回（{@code passed=false}），不抛异常；
 * 执行臂硬边界与权限级别仍然生效，验证器不得越权。</p>
 */
public interface ObjectiveVerifier {

    /** 验证器类型标识（与 {@code Plan.VerifySpec.type} 对齐：file / command / llm）。 */
    String type();

    /**
     * 是否支持校验该步骤。
     *
     * @param step 计划步骤
     * @param ctx  会话上下文
     * @return 支持返回 true
     */
    boolean supports(Plan.PlanStep step, ConversationCtx ctx);

    /**
     * 执行一次校验。
     *
     * @param step    计划步骤
     * @param ctx     会话上下文
     * @param execLog 本轮各子任务执行结果（供 LLM 判定使用，客观验证器一般忽略）
     * @return 验证结果
     */
    VerificationResult verify(Plan.PlanStep step, ConversationCtx ctx, String execLog);
}
