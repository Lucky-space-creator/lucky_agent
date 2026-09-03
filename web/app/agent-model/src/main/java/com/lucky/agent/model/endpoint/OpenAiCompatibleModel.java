package com.lucky.agent.model.endpoint;

import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * OpenAI 兼容模型接入（LangChain4j 官方 {@link OpenAiChatModel} 适配）。
 *
 * <p>直接委托 LangChain4j 官方 OpenAI 实现：协议构造、鉴权头、工具调用、思考模式
 * 均由官方 SDK 处理，本类只负责「本机配置 → 官方模型」的桥接与推理深度映射，不再手写 HTTP。
 * {@code baseUrl} 语义与官方一致：OpenAI 兼容 base_url（如 {@code https://api.deepseek.com}
 * 或 {@code https://.../compatible-mode/v1}），接口路径 {@code /chat/completions} 由 SDK 拼接。</p>
 */
@Slf4j
public class OpenAiCompatibleModel implements ChatModel {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);

    private final ModelConfig config;
    private final OpenAiChatModel delegate;

    public OpenAiCompatibleModel(ModelConfig config, InferenceDepth inferenceDepth) {
        this.config = config;
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(EndpointFormat.resolveUrl(config.endpointUrl()))
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(CALL_TIMEOUT)
                // 推理深度：OFF 明确关闭思考，其余档位开启并映射 effort
                .reasoningEffort(resolveReasoningEffort(inferenceDepth))
                .sendThinking(inferenceDepth != InferenceDepth.OFF);
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }
        this.delegate = builder.build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int msgCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        long start = System.currentTimeMillis();
        log.info("[模型调用] OpenAI 请求开始：model={} baseUrl={} messages={} tools={}",
                config.modelName(), config.endpointUrl(), msgCount, toolCount);
        // 点分工具名（file.read 等）不满足 OpenAI 安全名规则，发送前清洗、响应后还原
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
            log.info("[模型调用] OpenAI 请求成功：model={} cost={}ms finishReason={} "
                            + "inputTokens={} outputTokens={} toolCalls={}",
                    config.modelName(), cost, restored.finishReason(),
                    usage == null ? 0 : usage.inputTokenCount(),
                    usage == null ? 0 : usage.outputTokenCount(),
                    toolCalls);
            // 仅回填配置模型名（事件/指标展示用），复用官方响应完整对象，
            // 保留 AiMessage 的 thinking/attributes 等属性，供多轮思考模式回传
            return ChatResponse.builder()
                    .modelName(config.modelName())
                    .aiMessage(restored.aiMessage())
                    .tokenUsage(restored.tokenUsage())
                    .finishReason(restored.finishReason())
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[模型调用] OpenAI 请求失败：model={} baseUrl={} cost={}ms error={}",
                    config.modelName(), config.endpointUrl(), cost, e.getMessage());
            throw e;
        }
    }

    /**
     * 推理深度映射为 reasoning_effort：OFF 关闭；QUICK→low、BALANCED→medium、
     * DEEP/MAXIMUM→high。仅对支持推理参数的模型生效，普通模型会忽略未知参数。
     */
    private String resolveReasoningEffort(InferenceDepth inferenceDepth) {
        return switch (inferenceDepth) {
            case QUICK -> "low";
            case BALANCED -> "medium";
            case DEEP, MAXIMUM -> "high";
            default -> null;
        };
    }
}
