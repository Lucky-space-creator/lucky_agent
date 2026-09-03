package com.lucky.agent.model.api;

import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.model.api.dto.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;

/**
 * 模型端点接入契约。
 *
 * <p>端点经 LangChain4j ChatModel 适配后对下游透明；本地配置直连，不向平台上报 Key/URL。</p>
 */
@Remote(serviceName = "model-endpoint")
public interface ModelEndpoint {

    /** 端点配置。 */
    ModelConfig config();

    /** 适配为 LangChain4j ChatModel（构建后即可供编排引擎使用）。 */
    ChatModel toModel();

    /** 健康状态（探活结果）。 */
    boolean healthy();
}
