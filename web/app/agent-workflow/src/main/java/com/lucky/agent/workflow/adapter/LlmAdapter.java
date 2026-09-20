package com.lucky.agent.workflow.adapter;

import java.util.Map;

/**
 * LLM 调用适配器（集成缝隙）。
 * <p>工作流模块不直接持有模型客户端；LLM 节点的实际推理通过本接口委托，
 * 由宿主（如 agent-core / agent-model）注入真实实现。默认提供 {@link NoopLlmAdapter} 以便模块独立运行。</p>
 */
public interface LlmAdapter {

    /**
     * 单步确定性调用（工作流的 LLM 节点语义：固定提示 + 输入映射，不做自主多轮推理）。
     *
     * @param prompt    渲染后的提示词
     * @param variables 可用变量（供实现参考）
     * @return 模型输出文本
     */
    String complete(String prompt, Map<String, Object> variables);
}
