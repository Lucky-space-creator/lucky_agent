package com.lucky.agent.persona.api;

import com.lucky.agent.persona.api.dto.BehaviorParam;

/**
 * 行为参数引擎契约：将行为参数转为 system prompt 约束句片段。
 */
@com.lucky.agent.common.contract.Remote(serviceName = "behavior-engine")
public interface BehaviorEngine {

    /**
     * 渲染行为参数为 Prompt 约束句。
     *
     * @param param 行为参数
     * @return 约束句片段
     */
    String render(BehaviorParam param);
}
