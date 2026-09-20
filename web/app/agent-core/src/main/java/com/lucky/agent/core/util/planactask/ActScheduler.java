package com.lucky.agent.core.util.planactask;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.runtime.gateway.ModelGateway;
import reactor.core.publisher.Mono;

/**
 * ACT 阶段调度器：按计划逐步执行单步（每步复用同一引擎，避免双层嵌套循环失控）。
 *
 * <p>经 {@link ModelGateway} 而非直接持有 {@link com.lucky.agent.core.models.Engine}：
 * 这样<b>两个主循环（reactor / langgraph）</b>共用同一份模型调用韧性（重试退避 + 熔断），
 * 遗留主环也无需改动自身逻辑即可获得该能力。</p>
 */
public class ActScheduler {

    private final ModelGateway gateway;

    public ActScheduler(ModelGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 执行计划（MVP：以计划目标驱动 ACT 运行）。
     *
     * @param ctx  会话上下文
     * @param plan 计划
     * @return 执行结果
     */
    public Mono<EngineRunResult> execute(ConversationCtx ctx, Plan plan) {
        if (plan == null || plan.goal() == null) {
            return Mono.just(EngineRunResult.error(ctx.sessionId(), Phase.ACT, "计划为空，无法执行"));
        }
        ConversationCtx actCtx = ctx.withPhase(Phase.ACT);
        return Mono.fromCallable(() -> gateway.call(actCtx, Phase.ACT, plan.goal()));
    }
}
