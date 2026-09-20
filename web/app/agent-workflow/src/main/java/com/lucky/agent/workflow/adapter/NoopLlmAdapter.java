package com.lucky.agent.workflow.adapter;

import java.util.Map;

/**
 * 默认 LLM 适配器：不依赖任何模型，返回确定性回显。
 * <p>用于工作流模块「独立运行 / 单元测试」，真实模型接入由宿主注入 {@link LlmAdapter} 实现覆盖。</p>
 */
public class NoopLlmAdapter implements LlmAdapter {

    @Override
    public String complete(String prompt, Map<String, Object> variables) {
        String p = (prompt == null) ? "" : prompt;
        return "[noop-llm] " + (p.length() > 200 ? p.substring(0, 200) : p);
    }
}
