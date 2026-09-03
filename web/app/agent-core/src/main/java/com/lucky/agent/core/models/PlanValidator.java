package com.lucky.agent.core.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器：拒绝缺字段 / 无法解析 / 含越权步骤的计划（R3 闭环）。
 *
 * <p>校验失败返回错误列表，引擎据此触发重规划。</p>
 */
public class PlanValidator {

    private static final Set<String> VALID_TYPES = Set.of("file", "shell", "ask", "tool");

    /**
     * 校验计划是否合法。
     *
     * @param plan 计划
     * @return 合法返回 true
     */
    public boolean isValid(Plan plan) {
        return validate(plan).isEmpty();
    }

    /**
     * 校验计划并返回错误列表。
     *
     * @param plan 计划
     * @return 错误列表（空表示合法）
     */
    public List<String> validate(Plan plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("计划为空");
            return errors;
        }
        if (plan.goal() == null || plan.goal().isBlank()) {
            errors.add("缺少目标 goal");
        }
        if (plan.steps() == null || plan.steps().isEmpty()) {
            errors.add("缺少步骤 steps");
            return errors;
        }
        for (Plan.PlanStep step : plan.steps()) {
            if (step.type() == null || !VALID_TYPES.contains(step.type())) {
                errors.add("非法步骤类型：" + step.type());
            }
            if (step.desc() == null || step.desc().isBlank()) {
                errors.add("步骤 " + step.id() + " 缺少描述");
            }
        }
        return errors;
    }
}
