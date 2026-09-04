package com.lucky.agent.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.HookEvent;
import com.lucky.agent.common.dto.HookEventName;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.compact.CompactionPipeline;
import com.lucky.agent.core.compact.TokenMeter;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.engine.StepLimitGuard;
import com.lucky.agent.core.gateway.ToolGateway;
import com.lucky.agent.core.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.metrics.MetricsCollector;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.memory.api.MemoryRetriever;
import com.lucky.agent.memory.api.dto.RecallResult;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.model.prompt.BasePromptStore;
import com.lucky.agent.model.prompt.SystemPromptAssembler;
import com.lucky.agent.persona.api.PersonaService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一 REACT 引擎（LangChain4j 编排核心封装层，§4.4/§4.5）。
 * <p>PLAN/ACT/ASK 复用同一引擎，仅系统提示/目标/终止条件不同；LangChain4j 负责模型与工具抽象，
 * 本引擎只做「分层提示词组装 + 工具集注入 + 手动 REACT 循环 + 事件流适配」。不实现第二套推理循环。</p>
 */

@Slf4j
@Service
public class ReactEngine implements Engine {

    /** 工具参数解析复用同一实例（ObjectMapper 线程安全，避免每轮调用重复构造）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelRouter modelRouter;
    private final ToolGateway toolGateway;
    private final PersonaService personaService;
    private final MemoryRetriever memoryRetriever;
    private final ConversationStateManager stateManager;
    private final CoreProperties properties;
    private final CompactionPipeline compactionPipeline;
    private final TokenMeter tokenMeter;
    private final LifecycleHookDispatcher hookDispatcher;
    private final MetricsCollector metricsCollector;
    private final StepLimitGuard stepLimitGuard;
    private final BasePromptStore basePromptStore;
    private final double contextThreshold;

    public ReactEngine(ModelRouter modelRouter, ToolGateway toolGateway, PersonaService personaService,
                       MemoryRetriever memoryRetriever, ConversationStateManager stateManager,
                       CoreProperties properties,
                       CompactionPipeline compactionPipeline,
                       TokenMeter tokenMeter,
                       LifecycleHookDispatcher hookDispatcher,
                       MetricsCollector metricsCollector,
                       StepLimitGuard stepLimitGuard,
                       BasePromptStore basePromptStore,
                       @Value("${model.context-threshold:0.9}") double contextThreshold) {
        this.modelRouter = modelRouter;
        this.toolGateway = toolGateway;
        this.personaService = personaService;
        this.memoryRetriever = memoryRetriever;
        this.stateManager = stateManager;
        this.properties = properties;
        this.compactionPipeline = compactionPipeline;
        this.tokenMeter = tokenMeter;
        this.hookDispatcher = hookDispatcher;
        this.metricsCollector = metricsCollector;
        this.stepLimitGuard = stepLimitGuard;
        this.basePromptStore = basePromptStore;
        this.contextThreshold = contextThreshold;
    }

    @Override
    public Mono<EngineRunResult> run(ConversationCtx ctx, Phase phase, String goal) {
        return Mono.fromCallable(() -> execute(ctx, phase, goal))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private EngineRunResult execute(ConversationCtx ctx, Phase phase, String goal) {
        ConversationStateManager.SessionState state = stateManager.session(ctx.sessionRef());
        state.phase(phase);
        state.running(true);
        AgentEventPublisher publisher = stateManager.publisher();
        // 用户在界面选定的模型端点（未指定时由路由回退主端点）
        String modelId = modelIdOf(ctx);
        try {
            ChatModel model = modelRouter.resolve(modelId);
            String sysPrompt = assembleSystemPrompt(ctx, phase);
            List<ToolSpecification> tools = toolGateway.buildToolSpecifications(ctx.workspaceId(), ctx.goal());

            // 步数上限接线：PLAN/ACT 复用同一引擎，仅终止条件不同（StepLimitGuard 统一裁决）
            int maxSteps = stepLimitGuard.maxSteps(phase);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(sysPrompt));
            List<ChatMessage> history = new ArrayList<>(state.messages());
            if (history.isEmpty()) {
                history.add(UserMessage.from(goal == null ? ctx.goal() : goal));
            }
            messages.addAll(history);

            // 上下文压缩：占用率 → 阈值自动压缩（阈值触发，不等定时任务）
            messages = maybeCompact(ctx, messages, publisher);

            publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), "开始" + phase.name() + "阶段"));

            int step = 0;
            String finalText = null;
            long tokenUsed = 0;
            // 末次模型响应：落库时需保留 thinking（推理模型要求下一轮原样回传 reasoning_content）
            AiMessage lastAi = null;
            while (step < maxSteps) {
                step++;
                AiMessage ai;
                try {
                    ChatRequest request = ChatRequest.builder()
                            .messages(messages)
                            .toolSpecifications(tools)
                            .build();
                    ai = model.chat(request).aiMessage();
                } catch (Exception e) {
                    throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
                }
                // 真实指标：每次模型调用计 1；token 用量按本次调用增量上报（输入+输出），
                // 由指标收集器累加得到会话级总量，避免重复累计导致虚高
                metricsCollector.reportModelCall(ctx.sessionId());
                long inputTokens = tokenMeter.count(messages);
                long outputTokens = tokenMeter.count(ai);
                long callTokens = inputTokens + outputTokens;
                tokenUsed += callTokens;
                publisher.publish(ctx.sessionId(), AgentEvent.token(
                        ctx.sessionId(), callTokens, inputTokens, outputTokens,
                        modelRouter.contextWindow(modelId),
                        modelRouter.modelName(modelId), false));
                messages.add(ai);
                lastAi = ai;

                // 无工具调用 → 最终回复（思考在前，正文流式输出在后）
                if (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
                    // 思考内容（reasoning/thinking）作为 thought 事件推送，前端渲染在正文之前
                    String thinking = thinkingOf(ai);
                    if (thinking != null && !thinking.isBlank()) {
                        publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), thinking));
                    }
                    finalText = ai.text();
                    if (finalText != null && !finalText.isBlank()) {
                        finalText = streamFinalReply(ctx, finalText, publisher);
                    }
                    break;
                }

                // 有工具调用：把模型本轮的过程叙述（ai.text）推送到思考区，
                // 让用户在工具执行前后能看到 Agent 的推理与进展，而不只看到最终流式结果
                String narrate = ai.text();
                if (narrate != null && !narrate.isBlank()) {
                    publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), narrate));
                }

                // 有工具调用 → 批量分发（只读并行、写串行），按输入顺序回灌结果
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> reqs = ai.toolExecutionRequests();
                List<ToolGateway.ToolCall> calls = new ArrayList<>();
                for (ToolExecutionRequest req : reqs) {
                    calls.add(new ToolGateway.ToolCall(req.name(), parseArgs(req.arguments())));
                }
                List<ToolResult> batchResults = toolGateway.dispatchAll(calls, ctx);
                for (int i = 0; i < reqs.size(); i++) {
                    ToolExecutionRequest req = reqs.get(i);
                    Map<String, Object> args = parseArgs(req.arguments());
                    // Skill / MCP 工具触发事件（透明面板计数）
                    if (req.name().startsWith("skill.")) {
                        publisher.publish(ctx.sessionId(), AgentEvent.skillInvoke(
                                ctx.sessionId(), req.name(), "dispatched"));
                        metricsCollector.snapshot(ctx.sessionId()).recordSkill();
                    } else if (req.name().startsWith("mcp.")) {
                        publisher.publish(ctx.sessionId(), AgentEvent.mcpInvoke(
                                ctx.sessionId(), "mcp", req.name(), "dispatched"));
                        metricsCollector.snapshot(ctx.sessionId()).recordMcp();
                    }
                    publisher.publish(ctx.sessionId(), AgentEvent.action(
                            ctx.sessionId(), req.id(), req.name(), args, null));
                    hookDispatcher.fire(new HookEvent(HookEventName.POST_TOOL_USE, ctx.sessionId())
                            .toolName(req.name()).toolInput(args));
                    ToolResult toolResult = batchResults.get(i);
                    String resultText = toolResult.data() == null ? toolResult.error() : String.valueOf(toolResult.data());
                    if (toolResult.suspended()) {
                        // 高危操作需用户确认：发出 ask 事件并暂停循环
                        publishAsk(ctx, publisher, req.name(), args, resultText);
                        return EngineRunResult.of(ctx.sessionId(), phase, finalText,
                                tokenUsed, modelRouter.modelName(modelId), "ask");
                    }
                    publisher.publish(ctx.sessionId(), AgentEvent.toolResult(
                            ctx.sessionId(), req.id(), req.name(), true,
                            resultText == null || resultText.isBlank() ? "ok" : truncate(resultText, 300),
                            null, null));
                    messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), resultText == null ? "" : resultText));
                }
            }

            if (finalText != null && !finalText.isBlank()) {
                // 落库时保留 thinking：推理模型（deepseek-reasoner 等）要求下一轮把
                // reasoning_content 原样回传，用 AiMessage.from(text) 重建会丢掉思考链，
                // 导致下一轮请求被模型厂商拒绝。
                state.appendMessage(lastAi == null
                        ? AiMessage.from(finalText)
                        : lastAi.toBuilder().text(finalText).build());
            }
            // 编排模式（多步连续 run）下不逐次发布 stop，避免前端误将中间步骤当整轮结束而断连；
            // 收尾 stop 由编排层/会话层统一发布（单步运行时照常发布）。
            if (!isSuppressStop(ctx)) {
                publisher.publish(ctx.sessionId(), AgentEvent.stop(ctx.sessionId(), "success", finalText));
            }
            return EngineRunResult.of(ctx.sessionId(), phase, finalText, tokenUsed, modelRouter.modelName(modelId), "success");
        } catch (Exception e) {
            log.error("引擎运行失败：session={} phase={}", ctx.sessionId(), phase, e);
            publisher.publish(ctx.sessionId(), AgentEvent.error(
                    ctx.sessionId(), phase.name(), null, e.getMessage(), "错误:ASK"));
            return EngineRunResult.error(ctx.sessionId(), phase, e.getMessage());
        } finally {
            state.running(false);
        }
    }

    /**
     * 上下文占用率 → 阈值自动压缩（agent-model §7.4：阈值触发为主、周期兜底为辅）。
     */
    private List<ChatMessage> maybeCompact(ConversationCtx ctx,
                                           List<ChatMessage> messages, AgentEventPublisher publisher) {
        String modelId = modelIdOf(ctx);
        int window = modelRouter.contextWindow(modelId);
        if (window <= 0 || messages == null || messages.isEmpty()) {
            return messages;
        }
        long estimatedTokens = tokenMeter.count(messages);
        long budget = (long) (window * contextThreshold);
        publisher.publish(ctx.sessionId(), AgentEvent.token(ctx.sessionId(), estimatedTokens, window, modelRouter.modelName(modelId), false));
        if (estimatedTokens < budget) {
            return messages;
        }
        log.info("上下文接近上限：{} tokens / {}，触发压缩", estimatedTokens, window);
        publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), "上下文接近上限，正在压缩（保留关键结果）"));
        List<ChatMessage> compacted = compactionPipeline.compact(messages, Map.of("threshold", contextThreshold));
        return compacted == null ? messages : compacted;
    }

    /**
     * 五层提示词组装：基座 + 人格 + 阶段指令 + 权限约束 + 召回上下文（静态/动态分界）。
     *
     * <p>基座层不硬编码：取自 {@code <frameworkRoot>/LUCKY.md}（{@link BasePromptStore}），
     * 用户改完保存即生效。每次 run 只读一次小文件，不做进程内缓存，避免改完不生效。</p>
     */
    private String assembleSystemPrompt(ConversationCtx ctx, Phase phase) {
        SystemPromptAssembler assembler = new SystemPromptAssembler();
        assembler.base(basePromptStore.basePrompt());
        assembler.persona(personaService.renderPersonaLayer(personaId(ctx)));
        assembler.phase(phaseInstruction(phase));
        assembler.permission(permissionInstruction(ctx.permissionLevel()));
        RecallResult recall = memoryRetriever.recall(ctx.userId(), ctx.goal() == null ? "" : ctx.goal(), 5);
        if (recall != null && recall.entries() != null && !recall.entries().isEmpty()) {
            StringBuilder memory = new StringBuilder("【已知记忆】");
            recall.entries().forEach(e -> memory.append('\n').append("- [").append(e.track().code())
                    .append("] ").append(e.content()));
            assembler.memory(memory.toString());
        }
        return assembler.assemble();
    }

    private String personaId(ConversationCtx ctx) {
        Object personaId = ctx.extra().get("personaId");
        return personaId == null ? null : String.valueOf(personaId);
    }

    private String phaseInstruction(Phase phase) {
        return switch (phase) {
            case PLAN -> """
                    【阶段指令】当前为规划阶段：先产出结构化计划 JSON（goal/steps/canAutoExecute），不要执行任何操作。
                    每个步骤尽可能声明 verify 字段，说明「如何客观证明这一步做成了」：
                    - 产物类步骤用 {"type":"file","path":"产物相对路径","contains":"关键内容片段"}；
                    - 可校验步骤用 {"type":"command","command":"校验命令","contains":"期望输出"}，\
                    校验命令按退出码判定（构建/测试/接口状态码/数据库查询均可），仅在全部权限下执行；
                    无法客观校验的步骤省略 verify，交由整体判定。
                    关于拆解方式，二选一或并存：
                    1) 常规拆分：若问题可拆成同属你职责的连续步骤，用 "steps" 数组给出 id/type/desc/target/safe/verify；
                    2) 隔离子代理：仅当任务含多个互相独立、可并行/需隔离上下文的高复杂度子问题（如分别评审多模块、
                       分别生成多份独立产物）时，用顶层 "subagents" 数组声明，每项 {"id","name","task","tools","permissionMode"}，
                       框架会为每项启动一个隔离子代理执行并回传摘要；不要把本可由主链路步骤完成的普通拆解放进 subagents。
                    当拆分与子代理并存时，subagents 优先执行（先并行解决独立子问题），随后再用 steps 完成串行收尾。
                    子代理不可提升权限、只回摘要；不要在 subagents.task 里要求它去做需要整库上下文才能决策的事。""";
            case ACT -> "【阶段指令】当前为执行阶段：按计划逐步执行，工具调用过程中不要长篇输出过程细节；"
                    + "全部完成后，必须输出一段【简明总结】：概括本次完成的操作、修改/新增的文件路径、"
                    + "以及最终结论或建议，让用户一眼看懂结果，避免罗列中间过程。";
            case ASK -> "【阶段指令】当前为确认阶段：仅就高风险点向用户确认，不要继续执行。";
        };
    }

    private String permissionInstruction(PermissionLevel level) {
        if (level == null) {
            return "";
        }
        return "【权限约束】当前工作区权限级别：" + level.getLabel()
                + "（" + level.getCode() + "），严格遵守。"
                + switch (level) {
                    case READ_ONLY -> "只读，禁止任何写/删/执行操作";
                    case MODIFY -> "可修改文件，禁止执行命令";
                    case FULL -> "可读写并执行命令；全部权限下危险操作自动执行（受 deny 规则与执行臂硬边界兜底）";
                };
    }

    /** 工具挂起 → 发出 ask 事件（含 op 详情供前端确认）。 */
    private void publishAsk(ConversationCtx ctx, AgentEventPublisher publisher, String toolName,
                            Map<String, Object> args, String text) {
        String opType = toOpType(toolName);
        Object path = args.get("path");
        String target = path == null ? String.valueOf(args.getOrDefault("command", "")) : String.valueOf(path);
        Map<String, Object> op = new HashMap<>();
        op.put("opType", opType);
        op.put("path", target);
        op.put("args", args);
        String question = "高危操作需你确认：执行 " + toolName + "（" + target + "），是否继续？";
        publisher.publish(ctx.sessionId(), AgentEvent.ask(ctx.sessionId(), question, "HIGH", op));
    }

    private String toOpType(String toolName) {
        if (toolName == null) {
            return null;
        }
        return switch (toolName) {
            case "file.delete" -> "DELETE";
            case "shell.exec" -> "EXEC";
            case "file.write" -> "WRITE";
            case "file.rename" -> "RENAME";
            case "file.mkdir" -> "MKDIR";
            default -> null;
        };
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return unwrapArgs(OBJECT_MAPPER.readValue(arguments, Map.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 取出用户在界面选定的模型端点 id。
     * 未指定时返回 {@code null}，由 {@code ModelRouter} 回退到主端点。
     */
    private String modelIdOf(ConversationCtx ctx) {
        if (ctx == null || ctx.extra() == null) {
            return null;
        }
        Object raw = ctx.extra().get("modelId");
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 展平工具参数：工具定义把参数整体收敛在 {@code args} 对象下（见
     * {@code ToolGateway#buildParameters}），而工具实现是从顶层取字段的
     * （{@code str(args, "path")} 等），故在此展平后再分发。
     *
     * <p>模型直接给顶层参数（未包 {@code args}）时原样返回，两种写法都兼容；
     * {@code args} 被写成 JSON 字符串时再解析一次。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapArgs(Map<String, Object> parsed) {
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
            try {
                return OBJECT_MAPPER.readValue(raw, Map.class);
            } catch (Exception e) {
                log.warn("工具参数 args 不是合法 JSON：{}", raw);
                return Map.of();
            }
        }
        return parsed;
    }

    private String textOf(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return um.singleText();
        }
        if (msg instanceof AiMessage am) {
            return am.text();
        }
        if (msg instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        if (msg instanceof SystemMessage sm) {
            return sm.text();
        }
        return "";
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 是否处于编排多步运行（extra.suppressStop=true 时引擎不逐次发布 stop）。 */
    private boolean isSuppressStop(ConversationCtx ctx) {
        if (ctx == null || ctx.extra() == null) {
            return false;
        }
        Object v = ctx.extra().get("suppressStop");
        return v instanceof Boolean b && b;
    }

    /**
     * 提取模型思考内容（reasoning/thinking）。多数非推理模型返回 null，此时跳过思考展示。
     */
    private String thinkingOf(AiMessage ai) {
        if (ai == null) {
            return null;
        }
        try {
            return ai.thinking();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 最终回复流式输出：将已生成好的最终回复按小块切分，逐块发布 {@code CONTENT_DELTA} 事件，
     * 前端逐字渲染出打字机效果；返回完整文本作为最终答复。
     *
     * <p>当前 LangChain4j 1.19 的官方 {@code OpenAiChatModel} 未暴露流式 API，故采用
     * 文本分块 + 轻量延时实现视觉流式，不引入额外的模型流式请求，也不增加模型调用成本。</p>
     */
    private String streamFinalReply(ConversationCtx ctx, String fallback, AgentEventPublisher publisher) {
        String text = fallback == null ? "" : fallback;
        if (text.isEmpty()) {
            return text;
        }
        int chunk = 6;
        for (int i = 0; i < text.length(); i += chunk) {
            String delta = text.substring(i, Math.min(text.length(), i + chunk));
            publisher.publish(ctx.sessionId(), AgentEvent.contentDelta(ctx.sessionId(), delta));
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return text;
    }
}
