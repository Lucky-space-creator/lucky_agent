package com.lucky.agent.web.workflow.adapter;

import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.workflow.adapter.LlmAdapter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * LLM 节点适配器（宿主实现）：把工作流的 LLM 节点桥接到 {@link ModelRouter}。
 *
 * <p><b>为何不用 {@code ModelGateway}：</b>{@code ModelGateway.call(ctx, phase, goal)} 最终走
 * {@code Engine.run(...)}，即<b>完整 REACT 主回环</b>（多轮工具调用 + 自主推理）。
 * 而工作流 LLM 节点的语义是「固定提示 + 变量注入 → 单步确定性产出」
 * （见 {@code LlmNodeExecutor} 类注释：非 ReAct 自主循环）。两者语义不同：
 * 若接 Gateway，一个 LLM 节点会变成一次完整的 Agent 运行，既不可控也无法作为流程中的确定性环节。
 * 故此处直连 {@link ModelRouter#resolve()} 拿单步 {@code ChatModel}。</p>
 *
 * <p>与 {@code LlmMemorySummaryModel} 采用同一调用范式（{@code ChatRequest} + {@code ChatResponse}），
 * 保持模型层调用风格一致。</p>
 *
 * <p><b>失败语义：</b>未配置模型时 {@code ModelRouter.resolve()} 抛
 * {@code AgentException(MODEL_NOT_CONFIGURED)}；本适配器不吞异常，
 * 由 {@code LlmNodeExecutor} 的 try/catch 收敛为该节点的 FAILED 与可读错误信息，
 * 从而在运行实例里留下确切原因（而非静默产出空文本）。</p>
 */
@Slf4j
public class ModelRouterLlmAdapter implements LlmAdapter {

    /** LLM 节点输出长度上限（工作流单步产出，不需要长文）。 */
    private static final int MAX_OUTPUT_TOKENS = 2048;

    private final ModelRouter modelRouter;

    public ModelRouterLlmAdapter(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public String complete(String prompt, Map<String, Object> variables) {
        String safePrompt = (prompt == null) ? "" : prompt;
        ChatModel model = modelRouter.resolve();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from(safePrompt)))
                .build();

        ChatResponse response = model.chat(request);
        String text = (response == null || response.aiMessage() == null)
                ? null : response.aiMessage().text();
        if (text == null) {
            throw new IllegalStateException("模型返回空响应（endpoint 未返回可用文本）");
        }
        log.debug("工作流 LLM 节点调用完成：promptLength={} outputLength={}", safePrompt.length(), text.length());
        return text;
    }
}
