package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionResult;

import java.util.function.Supplier;

/**
 * 中间件：处理权限、预算、重试、日志、压缩、记忆等横切关注点。
 *
 * <p>钩子分两类：</p>
 * <ul>
 *   <li><b>before/after 通知型</b>：只做前置检查与后置记录，可用
 *       {@link MiddlewareContext#block(String)} 短路后续链（如预算耗尽、权限拒绝）；</li>
 *   <li><b>around 环绕型</b>：{@link #aroundTool(MiddlewareContext, Supplier)} 把「工具调用」
 *       包在中间件内部，因此能实现<b>重试、熔断、计时、降级</b>等必须包裹真实调用的关注点。
 *       环绕顺序由 {@link #order()} 升序决定，数值越小越靠外层。</li>
 * </ul>
 */
public interface Middleware {

    /** 顺序（升序执行，数值越小越靠前；对 around 钩子即越靠外层）。 */
    default int order() {
        return 0;
    }

    /** 名称（日志用）。 */
    default String name() {
        return getClass().getSimpleName();
    }

    default void onLoopStart(MiddlewareContext ctx) {
    }

    default void beforeLlm(MiddlewareContext ctx) {
    }

    default void afterLlm(MiddlewareContext ctx) {
    }

    default void beforeTool(MiddlewareContext ctx) {
    }

    default void afterTool(MiddlewareContext ctx) {
    }

    /**
     * 环绕工具调用：{@code next.get()} 触发内层中间件或最终的工具执行。
     *
     * <p>默认直通。实现方可多次调用 {@code next.get()}（重试）、完全跳过（缓存命中）、
     * 或替换结果（降级）。</p>
     *
     * @param ctx  中间件上下文（已带 toolCall）
     * @param next 内层调用
     * @return 工具执行结果
     */
    default ExecutionResult aroundTool(MiddlewareContext ctx, Supplier<ExecutionResult> next) {
        return next.get();
    }

    default void onLoopEnd(MiddlewareContext ctx) {
    }
}
