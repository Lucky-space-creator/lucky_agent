package com.lucky.agent.core.runtime.budget;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.runtime.RunBudget;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 单次运行的回合 / token 预算覆盖（per-run overrides）。
 *
 * <p><b>为什么需要这个类</b>：回合上限（{@code runMaxTurns}）与 token 预算（{@code runMaxBudget}）
 * 原先各自散落在 4 个读取点（会话级 {@code RunBudget}、{@code Orchestrator} 自建预算、
 * {@code RuntimeSessionFactory} 的 GLOBAL 作用域、{@code LangGraphOrchestrator} 直读 properties），
 * 载体各不相同时「只改一处」会造成静默不一致：例如通道传入的回合上限在 reactor 主环生效、
 * 在 langgraph 主环却不生效（其回合安全阀直接读全局 property）。本类把「覆盖如何取值」收敛为
 * <b>唯一一处纯函数</b>，各主环统一经由它取值，从构造上消除分叉。</p>
 *
 * <p><b>取值来源</b>：{@link ConversationCtx#extra()}。该 map 已贯穿三条主环
 * （{@code Orchestrator} / {@code ThinAgentLoop} / {@code LangGraphOrchestrator} 各自
 * {@code extra.putAll(base.extra())}），因此无需改动任何方法签名即可传递 run 级参数。
 * 通道侧只需把值放进 {@code UserInput.extra}，由 {@code ConversationManager#buildCtx} 按
 * {@link #PASSTHROUGH_KEYS} 白名单透传。</p>
 *
 * <p><b>安全语义（勿放宽）</b>：回合与预算是防失控的安全阀，本类<b>只允许收紧或适度放宽，不允许关闭</b>——
 * 传入非正数（含显式 0/负数）一律回落缺省值而非「不限制」。若解析失败同样回落缺省值并记 WARN 日志；
 * 通道侧须自行回显实际生效值，避免用户误以为覆盖已生效（呼应「覆盖必须可见」的约定）。
 * 权限级别、规则链、子代理深度/并发等安全阀不在本类可覆盖范围内（见 CLI 方案 §2.5）。</p>
 *
 * @param maxTurns  单次运行的回合上限（&gt; 0，永不为「不限制」）
 * @param maxBudget 单次运行的 token 预算（&le; 0 表示不限制，与 {@link CoreProperties#runMaxBudget()} 同义）
 */
@Slf4j
public record RunOverrides(int maxTurns, long maxBudget) {

    /** extra / {@code UserInput.extra} 中承载回合上限的键。 */
    public static final String KEY_MAX_TURNS = "maxTurns";

    /** extra / {@code UserInput.extra} 中承载 token 预算的键。 */
    public static final String KEY_MAX_BUDGET = "maxBudget";

    /**
     * 允许从通道逐次透传的键白名单。
     *
     * <p>刻意保持极窄：只放行「安全阀调节」两类参数。新增键必须先在方案文档中论证其安全性，
     * 并确认不属于权限/沙箱/并发等禁止覆盖项。</p>
     */
    public static final List<String> PASSTHROUGH_KEYS = List.of(KEY_MAX_TURNS, KEY_MAX_BUDGET);

    /** CoreProperties 缺失时的兜底回合上限（与 {@code CoreProperties} 紧凑构造器一致）。 */
    private static final int FALLBACK_MAX_TURNS = 30;

    /** 「不限制」的 token 预算取值（与 {@code CoreProperties} 约定一致）。 */
    private static final long UNLIMITED_BUDGET = -1L;

    /**
     * 从会话上下文解析本次运行的覆盖值；未提供或非法时回落 {@code properties} 的缺省值。
     *
     * @param ctx        会话上下文（{@code extra} 可空）
     * @param properties 核心配置（可空，空则用内置兜底值）
     * @return 解析后的覆盖值（两者均非「意外值」，可直接构造 {@link RunBudget} / {@link BudgetScope}）
     */
    public static RunOverrides from(ConversationCtx ctx, CoreProperties properties) {
        int defaultTurns = properties == null ? FALLBACK_MAX_TURNS : properties.runMaxTurns();
        long defaultBudget = properties == null ? UNLIMITED_BUDGET : properties.runMaxBudget();
        Map<String, Object> extra = ctx == null ? null : ctx.extra();
        return new RunOverrides(
                positiveInt(extra, KEY_MAX_TURNS, defaultTurns),
                positiveLong(extra, KEY_MAX_BUDGET, defaultBudget));
    }

    /** 本次运行的回合上限，供四级预算的 GLOBAL 作用域与回合安全阀共用（同一来源，不可分叉）。 */
    public int turnLimit() {
        return maxTurns;
    }

    /** 构造等价的一次性 {@link RunBudget}（reactor 主环自建预算场景）。 */
    public RunBudget toRunBudget() {
        return new RunBudget(maxTurns, maxBudget);
    }

    /**
     * 解析正整数覆盖值。非正数视为「试图关闭安全阀」，回落缺省值（防止误传 0 导致回合不限制）。
     */
    private static int positiveInt(Map<String, Object> extra, String key, int fallback) {
        Object raw = extra == null ? null : extra.get(key);
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            int v = (raw instanceof Number num) ? num.intValue() : Integer.parseInt(text);
            if (v > 0) {
                return v;
            }
            log.warn("忽略非法的 {}（{}），回合上限不允许关闭，回落缺省值 {}", key, text, fallback);
            return fallback;
        } catch (NumberFormatException e) {
            log.warn("无法解析 {}（{}），回落缺省值 {}", key, text, fallback);
            return fallback;
        }
    }

    /**
     * 解析 token 预算覆盖值。约定与 {@link CoreProperties#runMaxBudget()} 相同：
     * 非正数表示「不限制」，故此处仅做数字解析，不做正数限制。
     */
    private static long positiveLong(Map<String, Object> extra, String key, long fallback) {
        Object raw = extra == null ? null : extra.get(key);
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return (raw instanceof Number num) ? num.longValue() : Long.parseLong(text);
        } catch (NumberFormatException e) {
            log.warn("无法解析 {}（{}），回落缺省值 {}", key, text, fallback);
            return fallback;
        }
    }
}
