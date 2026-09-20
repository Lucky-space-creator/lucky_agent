package com.lucky.agent.core.runtime.strategy;

import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.verify.Verifier;

/**
 * 策略插件：决定「何时拆解步骤、何时启动子代理、选择哪个验证器」。
 * <p>运行时通过 Spring 注入 {@code List<StrategyPlugin>} 并按 {@link #order()} 取首个命中者，
 * 从而把原本硬编码在主循环里的策略判断外置为可插拔插件（对扩展开放、对修改封闭）。</p>
 */
public interface StrategyPlugin {

    default String name() {
        return getClass().getSimpleName();
    }

    /** 顺序（升序，数值越小优先级越高）。 */
    default int order() {
        return 0;
    }

    /** 是否适用于当前上下文。 */
    default boolean applies(RuntimeContext ctx) {
        return true;
    }

    /** 是否应把任务拆解为步骤。 */
    default boolean shouldDecompose(RuntimeContext ctx, String llmOutput) {
        return false;
    }

    /** 是否应启动隔离子代理。 */
    default boolean shouldSpawnSubAgents(RuntimeContext ctx, String llmOutput) {
        return false;
    }

    /** 选择验证器（默认沿用运行时默认验证器）。 */
    default Verifier verifier(RuntimeContext ctx, Verifier defaultVerifier) {
        return defaultVerifier;
    }
}
