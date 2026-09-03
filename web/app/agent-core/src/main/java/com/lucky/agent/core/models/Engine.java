package com.lucky.agent.core.models;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.dto.EngineRunResult;
import reactor.core.publisher.Mono;

/**
 * REACT 引擎入口契约。
 *
 * <p>PLAN / ACT / ASK 不是三个独立循环，而是同一个引擎的三种运行模式，
 * 仅系统提示 / 目标 / 终止条件不同（§4.4/§4.5）。</p>
 */
@Remote(serviceName = "engine")
public interface Engine {

    /**
     * 以指定阶段运行引擎。
     *
     * @param ctx   会话上下文
     * @param phase 阶段（PLAN / ACT / ASK）
     * @param goal  目标
     * @return 运行结果
     */
    Mono<EngineRunResult> run(ConversationCtx ctx, Phase phase, String goal);
}
