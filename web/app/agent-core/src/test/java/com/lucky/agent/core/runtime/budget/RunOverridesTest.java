package com.lucky.agent.core.runtime.budget;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.runtime.RunBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunOverrides} 单测：覆盖解析优先级、非法值回落与安全阀不可关闭三条不变量。
 *
 * <p>本测试是「per-run 覆盖」这一能力的安全网：覆盖值一旦被误解析为「不限制」，
 * 就等于通道可以关闭内核的防失控安全阀，因此「非正数一律回落缺省」必须有测试守住。</p>
 */
class RunOverridesTest {

    private static final int DEFAULT_TURNS = 7;
    private static final long DEFAULT_BUDGET = 5000L;

    private static CoreProperties properties() {
        // planMaxSteps, actMaxSteps, runMaxTurns, runMaxBudget, subagentEnabled, subagentMaxConcurrency,
        // subagentTaskTimeoutSec, orchestratorMaxIterations, orchestratorMaxRetries, orchestratorMode,
        // verificationEnabled, verificationTimeoutSec, retryBackoffMs, earlyStopConfidenceThreshold
        return new CoreProperties(12, 30, DEFAULT_TURNS, DEFAULT_BUDGET,
                false, 4, 300L, 5, 3, "reactor", true, 60L, 500L, 0.3);
    }

    private static ConversationCtx ctxWith(Map<String, Object> extra) {
        return ConversationCtx.builder()
                .sessionRef(SessionRef.of("s1", "u1", "w1"))
                .phase(Phase.ACT)
                .goal("g")
                .extra(extra)
                .build();
    }

    private static Map<String, Object> extra(Object maxTurns, Object maxBudget) {
        Map<String, Object> m = new HashMap<>();
        if (maxTurns != null) {
            m.put(RunOverrides.KEY_MAX_TURNS, maxTurns);
        }
        if (maxBudget != null) {
            m.put(RunOverrides.KEY_MAX_BUDGET, maxBudget);
        }
        return m;
    }

    @Test
    @DisplayName("未提供覆盖时，取值与 CoreProperties 缺省值完全一致（纯重构的行为不变量）")
    void shouldFallBackToPropertiesWhenNoOverride() {
        RunOverrides o = RunOverrides.from(ctxWith(Map.of()), properties());
        assertThat(o.maxTurns()).isEqualTo(DEFAULT_TURNS);
        assertThat(o.maxBudget()).isEqualTo(DEFAULT_BUDGET);
        assertThat(o.turnLimit()).isEqualTo(DEFAULT_TURNS);
    }

    @Test
    @DisplayName("extra 为空 map / ctx 为 null / properties 为 null 均不抛异常，按兜底值处理")
    void shouldHandleNullInputs() {
        assertThat(RunOverrides.from(ctxWith(null), properties()).maxTurns()).isEqualTo(DEFAULT_TURNS);
        assertThat(RunOverrides.from(null, properties()).maxTurns()).isEqualTo(DEFAULT_TURNS);
        assertThat(RunOverrides.from(null, null).maxTurns()).isEqualTo(30);
        assertThat(RunOverrides.from(null, null).maxBudget()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("数字型覆盖生效（回合收紧 + 预算放宽）")
    void shouldApplyNumericOverrides() {
        RunOverrides o = RunOverrides.from(ctxWith(extra(3, 100_000L)), properties());
        assertThat(o.maxTurns()).isEqualTo(3);
        assertThat(o.maxBudget()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("字符串型覆盖同样生效（通道以文本传参时不丢语义）")
    void shouldApplyTextOverrides() {
        RunOverrides o = RunOverrides.from(ctxWith(extra("3", "12345")), properties());
        assertThat(o.maxTurns()).isEqualTo(3);
        assertThat(o.maxBudget()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("安全阀不可关闭：maxTurns 传 0 或负数一律回落缺省值，而非「不限制」")
    void shouldNeverAllowDisablingTurnLimit() {
        assertThat(RunOverrides.from(ctxWith(extra(0, null)), properties()).maxTurns())
                .isEqualTo(DEFAULT_TURNS);
        assertThat(RunOverrides.from(ctxWith(extra(-5, null)), properties()).maxTurns())
                .isEqualTo(DEFAULT_TURNS);
        assertThat(RunOverrides.from(ctxWith(extra("0", null)), properties()).maxTurns())
                .isEqualTo(DEFAULT_TURNS);
        // 兜底路径（无 properties）同样不可关闭
        assertThat(RunOverrides.from(ctxWith(extra(0, null)), null).maxTurns()).isEqualTo(30);
    }

    @Test
    @DisplayName("非法文本不抛异常，回落缺省值")
    void shouldFallBackOnUnparsableText() {
        RunOverrides o = RunOverrides.from(ctxWith(extra("abc", "xyz")), properties());
        assertThat(o.maxTurns()).isEqualTo(DEFAULT_TURNS);
        assertThat(o.maxBudget()).isEqualTo(DEFAULT_BUDGET);
        assertThat(RunOverrides.from(ctxWith(extra("  ", "  ")), properties()).maxTurns())
                .isEqualTo(DEFAULT_TURNS);
    }

    @Test
    @DisplayName("token 预算沿用 CoreProperties 约定：非正数表示不限制（不做正数回落）")
    void shouldKeepNonPositiveBudgetAsUnlimited() {
        assertThat(RunOverrides.from(ctxWith(extra(null, 0L)), properties()).maxBudget()).isZero();
        assertThat(RunOverrides.from(ctxWith(extra(null, -1L)), properties()).maxBudget()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("toRunBudget 如实反映覆盖值：回合上限生效")
    void toRunBudgetShouldHonourOverride() {
        RunBudget budget = RunOverrides.from(ctxWith(extra(2, null)), properties()).toRunBudget();
        assertThat(budget.maxTurns()).isEqualTo(2);
        assertThat(budget.turnsExhausted()).isFalse();
        budget.incrementTurn();
        budget.incrementTurn();
        assertThat(budget.turnsExhausted()).isTrue();
    }

    @Test
    @DisplayName("透传白名单只含回合与预算两项（新增键必须先在方案文档论证）")
    void passthroughWhitelistShouldStayMinimal() {
        assertThat(RunOverrides.PASSTHROUGH_KEYS)
                .containsExactlyInAnyOrder(RunOverrides.KEY_MAX_TURNS, RunOverrides.KEY_MAX_BUDGET);
    }
}
