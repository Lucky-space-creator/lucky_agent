package com.lucky.agent.core.runtime.gateway;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.dto.EngineRunResult;

/**
 * 最内层网关：直接调用 {@link Engine}，把 {@code Mono} 阻塞求值，统一为同步契约。
 *
 * <p>异常不外抛而是转换为 {@link EngineRunResult#error} —— 与项目「错误即数据」的约定一致，
 * 让外层的重试/熔断装饰器只需判定结果，无需关心异常类型。</p>
 */
public class EngineModelGateway implements ModelGateway {

    private final Engine engine;

    public EngineModelGateway(Engine engine) {
        this.engine = engine;
    }

    @Override
    public EngineRunResult call(ConversationCtx ctx, Phase phase, String goal) {
        try {
            return engine.run(ctx, phase, goal).block();
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return EngineRunResult.error(ctx == null ? null : ctx.sessionId(), phase, message);
        }
    }
}
