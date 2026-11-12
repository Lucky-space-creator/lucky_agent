package com.lucky.agent.core.models;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划校验器：拒绝缺字段 / 无法解析的计划（R3 闭环）。
 *
 * <p>校验失败返回错误列表，引擎据此触发重规划。</p>
 *
 * <p><b>为什么不校验步骤类型</b>：{@code Plan.PlanStep#type()} 目前没有任何执行分支消费它
 * （真正决定能不能做的是执行臂的权限裁决与硬边界），此前按 {@code file/shell/ask/tool}
 * 白名单硬校验，模型只要写 {@code scan}/{@code review} 这类描述性类型，整份计划就被判非法，
 * 触发一次完整重规划，重规划又常常退化成「请确认…」的反问 —— 用户发一条「帮我审查代码」
 * 却连一步都没执行到。计划的可执行性不再靠类型白名单，改由执行期逐次裁决。</p>
 */
public class PlanValidator {

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
            if (step.desc() == null || step.desc().isBlank()) {
                errors.add("步骤 " + step.id() + " 缺少描述");
            }
        }
        return errors;
    }
}
