package com.lucky.agent.model.support.endpoint;

import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.lucky.agent.model.support.usage.ModelUsageTracker;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容模型接入（LangChain4j 官方 {@link OpenAiChatModel} 适配）。
 *
 * <p>直接委托 LangChain4j 官方 OpenAI 实现：协议构造、鉴权头、工具调用、思考模式
 * 均由官方 SDK 处理，本类只负责「本机配置 → 官方模型」的桥接与推理深度映射，不再手写 HTTP。
 * {@code baseUrl} 语义与官方一致：OpenAI 兼容 base_url（如 {@code https://api.deepseek.com}
 * 或 {@code https://.../compatible-mode/v1}），接口路径 {@code /chat/completions} 由 SDK 拼接。</p>
 *
 * <p><b>思考链回传（thinking 模式硬要求）</b>：DeepSeek 等推理模型在响应中返回
 * {@code reasoning_content}，并<b>要求下一轮请求把该字段原样回传</b>，否则第二次调用直接被拒
 * （{@code The `reasoning_content` in the thinking mode must be passed back to the API.}）。
 * LangChain4j 官方适配器需同时开启两个开关才能闭环：
 * {@code returnThinking(true)} 把响应中的 {@code reasoning_content} 解析进
 * {@code AiMessage.thinking()}，{@code sendThinking(true, "reasoning_content")} 在下一轮
 * 把 {@code AiMessage.thinking()} 以 {@code reasoning_content} 字段名写回 assistant 消息。
 * 二者缺一都会导致第二轮调用失败：只开 sendThinking 时 thinking 恒为空（没有回传内容），
 * 只开 returnThinking 时内容不会序列化到请求里。</p>
 *
 * <p><b>请求侧兜底（D15）</b>：模型偶尔会产出<b>没有思考链</b>的 assistant 消息（实测极短回答
 * 可返回空 reasoning_content），该消息留在会话历史里，随后任何带 tools 的请求都会被厂商 400
 * 拒绝并连锁污染整轮编排。为此本类在发送前经 {@link ReasoningPassbackSupport} 做一次请求副本
 * 归一化（思考模式下剔除/补占位），保证「带工具的请求不出现缺 reasoning_content 的 assistant」。
 * 只改发往厂商的副本，不动会话内存态与落盘。</p>
 */
@Slf4j
public class OpenAiCompatibleModel implements ChatModel, StreamingChatModel {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);

    /**
     * 思考链字段名：DeepSeek / Qwen / GLM 等 OpenAI 兼容厂商在 thinking 模式下的约定字段。
     * 官方 builder 的默认值即为该值，此处显式声明，避免升级 SDK 时默认值漂移导致思考链回传失效。
     */
    private static final String THINKING_FIELD = "reasoning_content";

    private final ModelConfig config;
    private final ModelUsageTracker usageTracker;
    private final OpenAiChatModel delegate;
    private final OpenAiStreamingChatModel streamingDelegate;
    /** 是否向端点请求了推理档位（OFF 不发 reasoning_effort，但很多推理模型仍会思考，故仅作初值）。 */
    private final boolean reasoningRequested;
    /** 端点是否已实测产出过思考链：一旦产出即视为思考模式端点，覆盖「OFF 档位仍思考」的场景。 */
    private volatile boolean thinkingObserved;

    public OpenAiCompatibleModel(ModelConfig config, InferenceDepth inferenceDepth,
                                 ModelUsageTracker usageTracker) {
        this.config = config;
        this.usageTracker = usageTracker;
        String reasoningEffort = resolveReasoningEffort(inferenceDepth);
        this.reasoningRequested = reasoningEffort != null;
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(EndpointFormat.resolveUrl(config.endpointUrl()))
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(CALL_TIMEOUT)
                // 推理深度：OFF 不发送该参数（注意不是「关闭思考」——DeepSeek 默认开思考且 effort=high、
                // Qwen3.8 默认 xhigh，不发送即沿用其默认高档位），其余档位映射 effort
                .reasoningEffort(reasoningEffort)
                // 思考链回传：与推理深度解耦，恒为 true。
                // 1) 模型本身是推理模型时（deepseek-reasoner 等）无论 effort 都会返回 reasoning_content，
                //    若按深度关闭回传，第二轮必然被拒；
                // 2) 非思考模型不会返回该字段，AiMessage.thinking() 恒为空，也就不会写出任何多余参数，
                //    对普通模型零影响。
                .returnThinking(true)
                .sendThinking(true, THINKING_FIELD);
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }
        this.delegate = builder.build();

        // 流式模型：与同步模型保持完全一致的配置（baseUrl/Key/思考回传/温度/上限），
        // 供引擎对最终回复做真实流式输出（LangChain4j 官方 SSE 增量回调）
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamingBuilder = OpenAiStreamingChatModel.builder()
                .baseUrl(EndpointFormat.resolveUrl(config.endpointUrl()))
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(CALL_TIMEOUT)
                .reasoningEffort(reasoningEffort)
                .returnThinking(true)
                .sendThinking(true, THINKING_FIELD);
        if (config.temperature() != null) {
            streamingBuilder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            streamingBuilder.maxTokens(config.maxTokens());
        }
        this.streamingDelegate = streamingBuilder.build();
    }

    /**
     * ChatModel 与 StreamingChatModel 双接口默认实现冲突（provider / supportedCapabilities
     * 各有默认值），显式统一为官方「OTHER / 无扩展能力」。
     */
    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return List.of();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().build();
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
        ChatRequest sanitized = ToolNameMappingSupport.sanitizeRequest(request, names);
        // 思考链回传保证：思考模式下带 tools 的请求不允许出现「缺 reasoning_content 的 assistant」，
        // 否则厂商直接 400（详见 ReasoningPassbackSupport 类注释）
        ChatRequest wireRequest = ReasoningPassbackSupport.ensureReasoningPassback(sanitized, thinkingMode());
        try {
            ChatResponse response = delegate.chat(wireRequest);
            ChatResponse restored = ToolNameMappingSupport.restoreResponse(response, names);
            long cost = System.currentTimeMillis() - start;
            var usage = restored.tokenUsage();
            int toolCalls = restored.aiMessage() != null
                    && restored.aiMessage().toolExecutionRequests() != null
                    ? restored.aiMessage().toolExecutionRequests().size() : 0;
            int thinkingLen = thinkingLength(restored.aiMessage());
            if (thinkingLen > 0) {
                thinkingObserved = true;
            }
            log.info("[模型调用] OpenAI 请求成功：model={} cost={}ms finishReason={} "
                            + "inputTokens={} outputTokens={} toolCalls={} thinkingLength={}",
                    config.modelName(), cost, restored.finishReason(),
                    usage == null ? 0 : usage.inputTokenCount(),
                    usage == null ? 0 : usage.outputTokenCount(),
                    toolCalls, thinkingLen);
            String replyText = restored.aiMessage() != null && restored.aiMessage().text() != null
                    ? restored.aiMessage().text() : "(无文本回复/仅工具调用)";
            log.info("[模型回复] model={} 长度={} 内容={}",
                    config.modelName(), replyText.length(),
                    replyText.length() > 3000 ? replyText.substring(0, 3000) + " …(已截断)" : replyText);
            // 用量统计：同步调用成功后上报 token 用量（流式路径在 complete 回调上报）
            if (usageTracker != null) {
                usageTracker.record(config.id(), config.modelName(),
                        usage == null ? 0 : usage.inputTokenCount(),
                        usage == null ? 0 : usage.outputTokenCount(), false);
            }
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
            if (usageTracker != null) {
                usageTracker.record(config.id(), config.modelName(), 0, 0, true);
            }
            throw e;
        }
    }

    /**
     * 流式对话：委托官方 {@link OpenAiStreamingChatModel}，SSE 增量回调实时透传。
     * 工具名清洗与响应还原与同步路径一致（{@link ToolNameMappingSupport}）。
     */
    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        ToolNameMapper names = new ToolNameMapper();
        ChatRequest sanitized = ToolNameMappingSupport.sanitizeRequest(request, names);
        // 与同步路径同一保证：流式请求同样可能带 tools，缺 reasoning_content 会被厂商拒绝
        ChatRequest wireRequest = ReasoningPassbackSupport.ensureReasoningPassback(sanitized, thinkingMode());
        streamingDelegate.chat(wireRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                handler.onPartialResponse(partial, context);
            }

            @Override
            public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
                // 流式响应的思考经增量回调送达，AiMessage 收尾时可能为空（实测），故在此置位
                if (thinking != null && thinking.text() != null && !thinking.text().isEmpty()) {
                    thinkingObserved = true;
                }
                handler.onPartialThinking(thinking, context);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (thinkingLength(response.aiMessage()) > 0) {
                    thinkingObserved = true;
                }
                ChatResponse restored = ToolNameMappingSupport.restoreResponse(response, names);
                // 用量统计：流式响应在 complete 时可拿到累计 tokenUsage
                if (usageTracker != null) {
                    var usage = restored.tokenUsage();
                    usageTracker.record(config.id(), config.modelName(),
                            usage == null ? 0 : usage.inputTokenCount(),
                            usage == null ? 0 : usage.outputTokenCount(), false);
                }
                handler.onCompleteResponse(restored);
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }

    /**
     * 思考链长度（用于确认 thinking 模式是否真的解析到 reasoning_content）。
     */
    private static int thinkingLength(AiMessage ai) {
        return ai == null || ai.thinking() == null ? 0 : ai.thinking().length();
    }

    /**
     * 端点是否处于思考模式：向厂商请求了推理档位，或历史响应已实测产出过思考链。
     *
     * <p>用于「请求携带 tools 时不得出现缺 reasoning_content 的 assistant」这一契约判定
     * （{@link ReasoningPassbackSupport}）：只看档位不够——OFF 档位下推理模型仍可能产出思考；
     * 只看实测也不够——会话第一轮尚未观测到思考时无法预判。二者取或即可。</p>
     */
    private boolean thinkingMode() {
        return reasoningRequested || thinkingObserved;
    }

    /**
     * 推理深度映射为 reasoning_effort：OFF 返回 null（不发送该参数，<b>不等于关闭思考</b>，
     * 厂商会落回自身默认档）；QUICK→low、BALANCED→medium、DEEP/MAXIMUM→high。
     *
     * <p>已知局限：本映射未区分厂商取值域——DeepSeek 把 medium 映射为 high，
     * 导致 BALANCED/DEEP/MAXIMUM 三档在 DeepSeek 上落点相同；Qwen3.8 把 high 映射为 xhigh，
     * 导致 DEEP/MAXIMUM 落点相同。档位归一化需端点能力元数据，尚未接入。</p>
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
