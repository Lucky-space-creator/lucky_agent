package com.lucky.agent.workflow.dto;

import com.lucky.agent.workflow.domain.enums.RunMode;

import java.util.Map;

/**
 * 触发工作流请求体。
 *
 * @param variables 初始变量
 * @param mode      执行模式（为空取默认）
 */
public record TriggerRequest(Map<String, Object> variables, RunMode mode) {

    public static TriggerRequest empty() {
        return new TriggerRequest(Map.of(), RunMode.SYNC);
    }
}
