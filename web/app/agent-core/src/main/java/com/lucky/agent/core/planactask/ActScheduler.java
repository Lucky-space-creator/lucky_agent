package com.lucky.agent.core.planactask;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.dto.EngineRunResult;
import reactor.core.publisher.Mono;

/**
 * ACT 阶段调度器：按计划逐步执行单步（每步复用同一引擎，避免双层嵌套循环失控）。
 */
public class ActScheduler {

    private final Engine engine;

    public ActScheduler(Engine engine) {
        this.engine = engine;
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
        return engine.run(ctx.withPhase(Phase.ACT), Phase.ACT, plan.goal());
    }
}
