package com.lucky.agent.core.util.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.repository.SubAgentResult;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * 子代理执行器：独立预算、串行执行、只回摘要。
 * <p>每个子代理绑定独立 {@code taskId}/{@code sessionId}，中间结果隔离；
 * 继承主 Agent 的工作区、权限级别与安全约束，不提升权限、不绕过 ASK 与执行臂硬边界。</p>
 *
 * <p><b>P2-1</b>：工具参数与主引擎一致地「展平 {@code args} 对象」后再分发，
 * 否则模型按 schema 传 {@code {"args":{...}}} 时工具从顶层取字段得到 null。</p>
 *
 * <p><b>P2-2</b>：上下文带工作区权限级别（工具裁决不收到 null level）；
 * {@code core.subagentTaskTimeoutSec} 实际接线为超时；步数耗尽未产出结果时返回失败而非「成功空摘要」。</p>
 */

@Slf4j
public class SubAgentExecutor {

    private final SubAgentFactory factory;
    private final LifecycleHookDispatcher hookDispatcher;
    private final ToolGateway toolGateway;
    private final WorkspaceConfig workspaceConfig;
    private final CoreProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SubAgentExecutor(SubAgentFactory factory, LifecycleHookDispatcher hookDispatcher,
                            ToolGateway toolGateway, WorkspaceConfig workspaceConfig,
                            CoreProperties properties) {
        this.factory = factory;
        this.hookDispatcher = hookDispatcher;
        this.toolGateway = toolGateway;
        this.workspaceConfig = workspaceConfig;
        this.properties = properties;
    }

    /**
     * 执行单个子代理任务并返回摘要。
     *
     * @param spec          子代理定义
     * @param task          任务描述
     * @param workspaceId   工作空间 ID
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
            // P2-2：子代理继承主 Agent 工作区的权限级别，工具调用的权限裁决不收到 null
            ConversationCtx ctx = ConversationCtx.builder()
                    .sessionRef(new SessionRef(parentSession + "-" + spec.id(), parentSession, workspaceId))
                    .goal(task)
                    .permissionLevel(workspaceConfig.permissionLevelOf(workspaceId)
                            .orElse(PermissionLevel.defaultValue()))
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
            // P2-2：步数耗尽但无最终产出 → 按失败返回，不允许「成功 + 空摘要」被主链路误聚合
            if (finalText == null || finalText.isBlank()) {
                log.warn("子代理步数耗尽未产出结果：{} maxTurns={}", spec.name(), runtime.maxTurns());
                hookDispatcher.fire(new com.lucky.agent.common.dto.HookEvent(
                        com.lucky.agent.common.dto.HookEventName.SUBAGENT_STOP, parentSession).toolName(spec.name()));
                return new SubAgentResult(spec.id(),
                        "子代理达到最大步数（" + runtime.maxTurns() + "）仍未产出结果", false);
            }
            log.info("子代理完成：{} 摘要长度={}", spec.name(), finalText.length());
            hookDispatcher.fire(new com.lucky.agent.common.dto.HookEvent(
                    com.lucky.agent.common.dto.HookEventName.SUBAGENT_STOP, parentSession).toolName(spec.name()));
            return new SubAgentResult(spec.id(), finalText, true);
        }).timeout(Duration.ofSeconds(Math.max(1, properties.subagentTaskTimeoutSec())))
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn("子代理执行超时：{} timeoutSec={}", spec.name(), properties.subagentTaskTimeoutSec());
                    return Mono.just(new SubAgentResult(spec.id(),
                            "子代理执行超时（>" + properties.subagentTaskTimeoutSec() + "s）", false));
                })
                .onErrorResume(e -> {
                    log.error("子代理执行失败：{}", spec.name(), e);
                    return Mono.just(new SubAgentResult(spec.id(), "子代理执行失败：" + e.getMessage(), false));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解析并展平工具参数：工具 schema 把参数整体收敛在 {@code args} 对象下，
     * 而工具实现从顶层取字段，故与主引擎一致先展平再分发（P2-1）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            if (parsed.size() != 1 || !parsed.containsKey("args")) {
                return parsed;
            }
            Object inner = parsed.get("args");
            if (inner instanceof Map<?, ?> nested) {
                return (Map<String, Object>) nested;
            }
            if (inner instanceof String raw && !raw.isBlank()) {
                return objectMapper.readValue(raw, Map.class);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("子代理工具参数解析失败：{}", e.getMessage());
            return Map.of();
        }
    }
}