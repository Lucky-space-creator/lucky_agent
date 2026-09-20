package com.lucky.agent.persona.service;

import com.lucky.agent.persona.repository.dto.BehaviorParam;

/**
 * 行为参数注入引擎：语气/详尽度/主动性 → Prompt 约束句。
 */
public class BehaviorParamEngine implements BehaviorEngine {

    @Override
    public String render(BehaviorParam param) {
        StringBuilder sb = new StringBuilder("【沟通风格】");
        boolean formal = param.tone() != null && "formal".equalsIgnoreCase(param.tone());
        sb.append(formal ? "严谨专业，先给结论再展开论证" : "轻松友好，贴近日常表达");
        if (param.verbosity() >= 0.7) {
            sb.append("；回答详尽完整，尽量覆盖上下文");
        } else if (param.verbosity() <= 0.3) {
            sb.append("；回答简洁克制，直击要点");
        }
        if (param.proactiveness() >= 0.7) {
            sb.append("；主动给出方案并推动执行");
        }
        return sb.toString();
    }
}
