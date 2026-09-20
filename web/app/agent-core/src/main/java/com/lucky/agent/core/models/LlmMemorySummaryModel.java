package com.lucky.agent.core.models;

import com.lucky.agent.memory.service.MemorySummaryModel;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 记忆管理 Agent 的 LLM 总结实现（{@link MemorySummaryModel} 的唯一接线实现）。
 *
 * <p>路由到用户配置的记忆专用端点（role=memory，P1-5：走 {@link ModelRouter#resolveMemory()}
 * 专用通道，不再被对话模型过滤逻辑拦截）；未配置时回退主力模型，保证记忆总结链路始终可用。</p>
 */
@Slf4j
@Service
public class LlmMemorySummaryModel implements MemorySummaryModel {

    /** 记忆总结输出长度上限（记忆条目精炼即可，控制成本）。 */
    private static final int MAX_OUTPUT_TOKENS = 1200;

    private final ModelRouter modelRouter;

    public LlmMemorySummaryModel(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public String summarize(String instruction, String content) {
        String modelId = modelRouter.memoryModelId().orElse(null);
        ChatModel model = modelRouter.resolveMemory();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(instruction),
                        UserMessage.from(content == null ? "" : content)))
                .parameters(ChatRequestParameters.builder()
                        .maxOutputTokens(MAX_OUTPUT_TOKENS)
                        .build())
                .build();
        ChatResponse response = model.chat(request);
        String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
        log.info("记忆总结完成：model={}（记忆管理 Agent 专用端点={}）",
                modelRouter.memoryModelName(), modelId);
        return text;
    }
}
