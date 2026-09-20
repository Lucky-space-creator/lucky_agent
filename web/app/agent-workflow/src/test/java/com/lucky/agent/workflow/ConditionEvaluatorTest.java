package com.lucky.agent.workflow;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.engine.ConditionEvaluator;
import com.lucky.agent.workflow.engine.MappingEvaluator;
import com.lucky.agent.workflow.exception.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 条件表达式与连接器求值测试。 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator(new MappingEvaluator());

    private VariableScope scope() {
        VariableScope scope = new VariableScope();
        scope.set("amount", 150);
        scope.set("status", "PAID");
        scope.set("count", 5);
        scope.set("enabled", true);
        scope.set("vip", true);
        scope.set("flag", false);
        scope.set("user", Map.of("profile", Map.of("level", 3)));
        return scope;
    }

    @Test
    void numericComparison() {
        assertThat(evaluator.evaluate("amount > 100", scope())).isTrue();
        assertThat(evaluator.evaluate("amount < 100", scope())).isFalse();
        assertThat(evaluator.evaluate("amount >= 150", scope())).isTrue();
    }

    @Test
    void stringComparison() {
        assertThat(evaluator.evaluate("status == 'PAID'", scope())).isTrue();
        assertThat(evaluator.evaluate("status != 'PAID'", scope())).isFalse();
    }

    @Test
    void logicalOperators() {
        assertThat(evaluator.evaluate("count >= 3 && enabled == true", scope())).isTrue();
        assertThat(evaluator.evaluate("amount > 1000 || vip == true", scope())).isTrue();
        assertThat(evaluator.evaluate("!flag", scope())).isTrue();
        assertThat(evaluator.evaluate("(amount > 100 && status == 'PAID') || count > 10", scope())).isTrue();
    }

    @Test
    void nestedPath() {
        assertThat(evaluator.evaluate("user.profile.level == 3", scope())).isTrue();
    }

    @Test
    void blankIsUnconditional() {
        assertThat(evaluator.evaluate("", scope())).isTrue();
        assertThat(evaluator.evaluate(null, scope())).isTrue();
    }

    @Test
    void invalidExpressionThrows() {
        assertThatThrownBy(() -> evaluator.evaluate("amount @ 10", scope()))
                .isInstanceOf(WorkflowException.class);
    }
}
