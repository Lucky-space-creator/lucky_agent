package com.lucky.agent.core.runtime.gateway;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.dto.EngineRunResult;

/**
 * 模型调用网关：把「调用模型」这一动作抽象出来，使重试/退避/熔断/超时等韧性能力
 * 可以像中间件包裹工具调用那样包裹模型调用。
 *
 * <p>为什么需要单独一层：模型 API 调用是整条链路中<b>最贵、最易失败</b>的外部依赖
 * （限流 429、网关 5xx、连接重置、超时），而既有的 {@code RetryMiddleware} 只能包裹
 * <b>工具</b>调用。本抽象补上模型侧的缺口，并让两个主循环（thin / langgraph）共用同一份实现。</p>
 *
 * <p>装饰器形态使能力可叠加且顺序可控：</p>
 * <pre>
 * CircuitBreakerModelGateway( RetryModelGateway( EngineModelGateway ) )
 * </pre>
 * 即「先熔断判定 → 再重试退避 → 最后真实调用」。熔断在最外层，因此在 OPEN 状态下
 * 重试会被立即短路，不会把重试放大成打爆下游。
 */
@FunctionalInterface
public interface ModelGateway {

    /**
     * 调用模型并等待结果（阻塞式：两个主循环均为同步编排）。
     *
     * @param ctx   会话上下文
     * @param phase 阶段
     * @param goal  目标
     * @return 引擎运行结果（失败以 {@link EngineRunResult#error} 表达，不抛异常）
     */
    EngineRunResult call(ConversationCtx ctx, Phase phase, String goal);
}
