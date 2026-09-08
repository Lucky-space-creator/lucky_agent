package com.lucky.agent.model.endpoint;

import com.lucky.agent.model.api.ModelEndpoint;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 端点接入适配（ModelEndpoint 实现）：本地配置直连 → LangChain4j 官方 ChatModel。
 * <p>每次调用重建模型（不再缓存），保证推理深度修改后立即生效。</p>
 */
public class ModelEndpointImpl implements ModelEndpoint {

    private final ModelConfig config;
    private final HealthProbe healthProbe;
    private final InferenceDepth inferenceDepth;

    public ModelEndpointImpl(ModelConfig config, HealthProbe healthProbe, InferenceDepth inferenceDepth) {
        this.config = config;
        this.healthProbe = healthProbe;
        this.inferenceDepth = inferenceDepth;
    }

    @Override
    public ModelConfig config() {
        return config;
    }

    @Override
    public ChatModel toModel() {
        return EndpointFormat.fromUrl(config.endpointUrl()) == EndpointFormat.ANTHROPIC
                ? new AnthropicCompatibleModel(config, inferenceDepth)
                : new OpenAiCompatibleModel(config, inferenceDepth);
    }

    @Override
    public StreamingChatModel toStreamingModel() {
        // 兼容模型类同时实现 ChatModel + StreamingChatModel，复用同一实例即可
        return (StreamingChatModel) toModel();
    }

    @Override
    public boolean healthy() {
        return healthProbe.isHealthy(config.id());
    }
}
