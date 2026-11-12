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

    /**
     * 描述性步骤类型不再判非法（回归：模型写 scan/review 就整份计划被拒 → 重规划 → 退化成反问）。
     * 类型没有任何执行分支消费，可执行性由执行期权限裁决决定。
     */
    @Test
    void testDescriptiveStepType_Passes() {
        Plan plan = new Plan("目标", List.of(
                new Plan.PlanStep(1, "scan", "扫描模块结构", null, true),
                new Plan.PlanStep(2, "review", "逐模块评审", null, true)), true);
        assertTrue(validator.isValid(plan));
    }

    /** 缺描述仍然是硬错误（执行时不知道该步做什么）。 */
    @Test
    void testMissingStepDesc_Fails() {
        Plan plan = new Plan("目标", List.of(
                new Plan.PlanStep(1, "file", " ", null, true)), false);
        assertFalse(validator.isValid(plan));
    }

    @Test
    void testEmptySteps_Fails() {
        Plan plan = new Plan("目标", List.of(), false);
        assertFalse(validator.isValid(plan));
    }
}
