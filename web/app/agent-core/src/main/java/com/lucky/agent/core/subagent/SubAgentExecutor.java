package com.lucky.agent.core.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.gateway.ToolGateway;
import com.lucky.agent.core.hook.LifecycleHookDispatcher;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 子代理执行器：独立预算、串行执行、只回摘要。
 * <p>每个子代理绑定独立 {@code taskId}/{@code sessionId}，中间结果隔离；
 * 继承主 Agent 的工作区、权限级别与安全约束，不提升权限、不绕过 ASK 与执行臂硬边界。</p>
 */

@Slf4j
public class SubAgentExecutor {

    
    private final SubAgentFactory factory;
    private final LifecycleHookDispatcher hookDispatcher;
    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubAgentExecutor(SubAgentFactory factory, LifecycleHookDispatcher hookDispatcher,
                            ToolGateway toolGateway) {
        this.factory = factory;
        this.hookDispatcher = hookDispatcher;
        this.toolGateway = toolGateway;
    }

    /**
     * 执行单个子代理任务并返回摘要。
     *
     * @param spec        子代理定义
     * @param task        任务描述
     * @param workspaceId 工作空间 ID
     * @param parentSession 主会话 ID
     * @return 子代理结果
     */
    public Mono<SubAgentResult> execute(SubAgentSpec spec, String task, String workspaceId, String parentSession) {
        return Mono.fromCallable(() -> {
            String sysPrompt = "你是子代理程序「" + spec.name() + "」。只完成指定子任务，最终只返回结果摘要。\n"
                    + (spec.description() == null ? "" : spec.description() + "\n")
                    + "任务：" + task;
            hookDispatcher.dispatch(new com.lucky.agent.common.dto.HookEvent(
                    com.lucky.agent.common.dto.HookEventName.SUBAGENT_START, parentSession).toolName(spec.name()));
            SubAgentFactory.SubAgentRuntime runtime = factory.create(spec, workspaceId, sysPrompt);
            ConversationCtx ctx = ConversationCtx.builder()
                    .sessionRef(new SessionRef(parentSession + "-" + spec.id(), parentSession, workspaceId))
                    .goal(task)
                    .build();

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(sysPrompt));
            messages.add(UserMessage.from(task));

            String finalText = null;
            for (int step = 0; step < runtime.maxTurns(); step++) {
                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(runtime.tools())
                        .build();
                AiMessage ai = runtime.model().chat(request).aiMessage();
                messages.add(ai);
                if (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
                    finalText = ai.text();
                    break;
                }
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    ToolResult toolResult = toolGateway.dispatch(req.name(), parseArgs(req.arguments()), ctx);
                    String resultText = toolResult.data() == null ? toolResult.error() : String.valueOf(toolResult.data());
                    messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), resultText == null ? "" : resultText));
                }
            }
            log.info("子代理完成：{} 摘要长度={}", spec.name(), finalText == null ? 0 : finalText.length());
            hookDispatcher.fire(new com.lucky.agent.common.dto.HookEvent(
                    com.lucky.agent.common.dto.HookEventName.SUBAGENT_STOP, parentSession).toolName(spec.name()));
            return new SubAgentResult(spec.id(), finalText == null ? "" : finalText, true);
        }).onErrorResume(e -> {
            log.error("子代理执行失败：{}", spec.name(), e);
            return Mono.just(new SubAgentResult(spec.id(), "子代理执行失败：" + e.getMessage(), false));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
