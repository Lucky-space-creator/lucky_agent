package com.lucky.agent.model.endpoint;

import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Anthropic 兼容模型接入（LangChain4j 官方 {@link AnthropicChatModel} 适配）。
 *
 * <p>直接委托 LangChain4j 官方 Anthropic 实现：{@code /v1/messages} 协议、{@code x-api-key}
 * 鉴权、工具调用、思考块回传均由官方 SDK 处理，本类只做「本机配置 → 官方模型」桥接与推理深度映射，
 * 不再手写 HTTP。{@code baseUrl} 语义与官方一致：Anthropic 兼容 base_url
 * （如 DeepSeek 的 {@code https://api.deepseek.com/anthropic}），消息路径由 SDK 拼接。</p>
 */
@Slf4j
public class AnthropicCompatibleModel implements ChatModel {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);

    private final ModelConfig config;
    private final AnthropicChatModel delegate;

    public AnthropicCompatibleModel(ModelConfig config, InferenceDepth inferenceDepth) {
        this.config = config;
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .baseUrl(EndpointFormat.resolveUrl(config.endpointUrl()))
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(CALL_TIMEOUT);
        // 推理深度：OFF 不启用扩展思考；其余档位映射 thinking 预算
        Integer maxTokens = config.maxTokens() == null ? 4096 : config.maxTokens();
        builder.maxTokens(maxTokens);
        AnthropicChatModel.AnthropicChatModelBuilder thinkingBuilder = resolveThinking(builder, inferenceDepth, maxTokens);
        if (config.temperature() != null) {
            // Anthropic 约束：启用 thinking 时省略 temperature（官方 SDK 会按需处理）
            thinkingBuilder.temperature(config.temperature());
        }
        this.delegate = thinkingBuilder.build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int msgCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        long start = System.currentTimeMillis();
        log.info("[模型调用] Anthropic 请求开始：model={} baseUrl={} messages={} tools={}",
                config.modelName(), config.endpointUrl(), msgCount, toolCount);
        // 点分工具名（file.read 等）不满足 Anthropic 安全名规则，发送前清洗、响应后还原
        ToolNameMapper names = new ToolNameMapper();
        ChatRequest wireRequest = ToolNameMappingSupport.sanitizeRequest(request, names);
        try {
            ChatResponse response = delegate.chat(wireRequest);
            ChatResponse restored = ToolNameMappingSupport.restoreResponse(response, names);
            long cost = System.currentTimeMillis() - start;
            var usage = restored.tokenUsage();
            int toolCalls = restored.aiMessage() != null
                    && restored.aiMessage().toolExecutionRequests() != null
                    ? restored.aiMessage().toolExecutionRequests().size() : 0;
            log.info("[模型调用] Anthropic 请求成功：model={} cost={}ms finishReason={} "
                            + "inputTokens={} outputTokens={} toolCalls={}",
                    config.modelName(), cost, restored.finishReason(),
                    usage == null ? 0 : usage.inputTokenCount(),
                    usage == null ? 0 : usage.outputTokenCount(),
                    toolCalls);
            // 仅回填配置模型名（事件/指标展示用），复用官方响应完整对象，
            // 保留 AiMessage 的 thinking/signature 等属性，供多轮思考模式回传
            return ChatResponse.builder()
                    .modelName(config.modelName())
                    .aiMessage(restored.aiMessage())
                    .tokenUsage(restored.tokenUsage())
                    .finishReason(restored.finishReason())
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[模型调用] Anthropic 请求失败：model={} baseUrl={} cost={}ms error={}",
                    config.modelName(), config.endpointUrl(), cost, e.getMessage());
            throw e;
        }
    }

    /**
     * 推理深度映射为 Anthropic 扩展思考：OFF 关闭；QUICK→2048、BALANCED→8192、
     * DEEP→16384、MAXIMUM→32768 预算。budget_tokens 必须 &lt; max_tokens，故按上界收敛。
     */
    private AnthropicChatModel.AnthropicChatModelBuilder resolveThinking(
            AnthropicChatModel.AnthropicChatModelBuilder builder,
            InferenceDepth inferenceDepth, int maxTokens) {
        if (inferenceDepth == InferenceDepth.OFF || maxTokens <= 2048) {
            return builder;
        }
        int budget = switch (inferenceDepth) {
            case QUICK -> 2048;
            case BALANCED -> 8192;
            case DEEP -> 16384;
            default -> 32768;
        };
        return builder
                .thinkingType("enabled")
                .thinkingBudgetTokens(Math.max(1024, Math.min(budget, maxTokens - 1024)));
    }
}
