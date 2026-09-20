package com.lucky.agent.core.runtime.middleware;

/**
 * 内置中间件顺序常量（数值越小越靠外）。
 *
 * <p>对环绕钩子 {@link Middleware#aroundTool} 而言，外层先生效、后收到结果，
 * 因此嵌套形态为：<br>
 * {@code 权限 → 预算 → 重试 → 压缩 → 记忆召回 → 快照 → 日志 → 工具实现}</p>
 *
 * <p>自定义中间件可在任意区间插入间隙值，无需改动既有代码。</p>
 */
public final class MiddlewareOrder {

    /** 权限裁决：最外层，deny 优先、越权转 ASK，且绝不被重试放大。 */
    public static final int PERMISSION = -400;

    /** 预算熔断：全局/任务预算耗尽即拦截。 */
    public static final int BUDGET = -100;

    /** 重试退避：包裹真实调用（权限与预算之外）。 */
    public static final int RETRY = 500;

    /** 上下文压缩：token 超阈值时触发压缩。 */
    public static final int COMPACTION = 600;

    /** 记忆召回：LLM 调用前注入召回内容。 */
    public static final int MEMORY_RECALL = 700;

    /** 状态快照：每轮结构化快照，供 traceId 回放。 */
    public static final int SNAPSHOT = 900;

    /** 日志与进度事件：最内层，逐次重试均可观测。 */
    public static final int LOGGING = 1000;

    private MiddlewareOrder() {
    }
}
