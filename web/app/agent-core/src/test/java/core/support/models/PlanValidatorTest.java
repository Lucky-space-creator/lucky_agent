package core.support.models;

import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.PlanValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanValidator 计划校验单元测试。
 */
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void testValidPlan_Passes() {
        Plan plan = new Plan("分析目录", List.of(
                new Plan.PlanStep(1, "file", "列出目录", "src", true),
                new Plan.PlanStep(2, "tool", "汇总结果", null, true)), false);
        assertTrue(validator.isValid(plan));
    }

    @Test
    void testMissingGoal_Fails() {
        Plan plan = new Plan("", List.of(
                new Plan.PlanStep(1, "file", "列出目录", "src", true)), false);
        assertFalse(validator.isValid(plan));
    }

    @Test
    void testInvalidStepType_Fails() {
        Plan plan = new Plan("目标", List.of(
                new Plan.PlanStep(1, "network", "越权步骤", null, true)), false);
        assertFalse(validator.isValid(plan));
    }

    @Test
    void testEmptySteps_Fails() {
        Plan plan = new Plan("目标", List.of(), false);
        assertFalse(validator.isValid(plan));
    }
}
