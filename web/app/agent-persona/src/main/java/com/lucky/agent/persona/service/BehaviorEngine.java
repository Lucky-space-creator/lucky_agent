package com.lucky.agent.persona.service;

import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.persona.repository.dto.BehaviorParam;

/**
 * 行为参数引擎契约：将行为参数转为 system prompt 约束句片段。
 */
@Remote(serviceName = "behavior-engine")
public interface BehaviorEngine {

    /**
     * 渲染行为参数为 Prompt 约束句。
     *
     * @param param 行为参数
     * @return 约束句片段
     */
    String render(BehaviorParam param);
}
