package com.lucky.agent.core.util.planactask;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.dto.EngineRunResult;
import reactor.core.publisher.Mono;

/**
 * 重规划器：计划非法时触发重新规划（R3 闭环）。
 */
public class Replanner {

    private final Engine engine;

    public Replanner(Engine engine) {
        this.engine = engine;
    }

    /**
     * 重新规划。
     *
     * @param ctx    会话上下文
     * @param goal   目标
     * @param reason 重新规划原因
     * @return 重规划结果
     */
    public Mono<EngineRunResult> replan(ConversationCtx ctx, String goal, String reason) {
        return engine.run(ctx.withPhase(Phase.PLAN), Phase.PLAN,
                "上轮计划非法（" + reason + "），请重新产出合法计划：" + goal);
    }
}
