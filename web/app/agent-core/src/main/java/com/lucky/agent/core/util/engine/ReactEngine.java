package com.lucky.agent.core.util.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.HookEvent;
import com.lucky.agent.common.dto.HookEventName;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.util.compact.TokenMeter;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.core.util.memory.MemoryPrefetcher;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.util.compact.CompactionPipeline;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.memory.service.MemoryRetriever;
import com.lucky.agent.memory.api.dto.RecallResult;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.core.config.PresetResolver;
import com.lucky.agent.model.support.prompt.BasePromptStore;
import com.lucky.agent.model.support.prompt.RuleStore;
import com.lucky.agent.model.support.prompt.SystemPromptAssembler;
import com.lucky.agent.persona.service.PersonaService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
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

    /** 流式最终回复超时上限（秒）：超过则放弃流式、回退伪流式，避免界面长时间空转。 */
    private static final long CALL_TIMEOUT_SECONDS = 180;

    /** 工具结果回灌模型的最大字符数：300 过小导致模型看不到文件完整内容而反复查询；提高到 20k 并标注截断。 */
    private static final int MAX_TOOL_RESULT_TEXT = 20000;

    /** 规划阶段容忍「模型仍在调工具」的轮数上限；达到即判定穷举并放弃本轮规划。 */
    private static final int PLAN_TOOL_TURN_LIMIT = 2;

    /** 规划阶段工具被驳回时回灌给模型的说明（要给出替代动作，否则模型会换个工具继续试）。 */
    private static final String PLAN_TOOL_DENIED =
            "规划阶段禁止调用工具：本阶段只产出计划或直接结论，不读取、不列举、不执行。"
                    + "请立即基于已知信息用文字回答；需要工具才能完成的部分写进计划的步骤里。";

    private final ModelRouter modelRouter;
    private final ToolGateway toolGateway;
    private final PersonaService personaService;
    private final WorkspaceConfig workspaceConfig;
    private final MemoryRetriever memoryRetriever;
    private final HierarchyMemoryRetriever hierarchyMemoryRetriever;
    private final MemoryPrefetcher memoryPrefetcher;
    private final MemoryMdProperties memoryMdProps;
    private final ConversationStateManager stateManager;
    private final CoreProperties properties;
    private final CompactionPipeline compactionPipeline;
    private final TokenMeter tokenMeter;
    private final LifecycleHookDispatcher hookDispatcher;
    private final MetricsCollector metricsCollector;
    private final StepLimitGuard stepLimitGuard;
    private final BasePromptStore basePromptStore;
    private final RuleStore ruleStore;
    private final PresetResolver presetResolver;
    private final double contextThreshold;

    public ReactEngine(ModelRouter modelRouter, ToolGateway toolGateway, PersonaService personaService,
                       WorkspaceConfig workspaceConfig,
                       MemoryRetriever memoryRetriever, HierarchyMemoryRetriever hierarchyMemoryRetriever,
                       MemoryPrefetcher memoryPrefetcher, MemoryMdProperties memoryMdProps,
                       ConversationStateManager stateManager,
                       CoreProperties properties,
                       CompactionPipeline compactionPipeline,
                       TokenMeter tokenMeter,
                       LifecycleHookDispatcher hookDispatcher,
                       MetricsCollector metricsCollector,
                       StepLimitGuard stepLimitGuard,
                       BasePromptStore basePromptStore,
                       RuleStore ruleStore,
                       PresetResolver presetResolver,
                       @Value("${model.context-threshold:0.9}") double contextThreshold) {
        this.modelRouter = modelRouter;
        this.toolGateway = toolGateway;
        this.personaService = personaService;
        this.workspaceConfig = workspaceConfig;
        this.memoryRetriever = memoryRetriever;
        this.hierarchyMemoryRetriever = hierarchyMemoryRetriever;
        this.memoryPrefetcher = memoryPrefetcher;
        this.memoryMdProps = memoryMdProps;
        this.stateManager = stateManager;
        this.properties = properties;
        this.compactionPipeline = compactionPipeline;
        this.tokenMeter = tokenMeter;
        this.hookDispatcher = hookDispatcher;
        this.metricsCollector = metricsCollector;
        this.stepLimitGuard = stepLimitGuard;
        this.basePromptStore = basePromptStore;
        this.ruleStore = ruleStore;
        this.presetResolver = presetResolver;
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
        // P1-1 fix：外层（ConversationManager）已置 running=true 作为会话级并发闸；
        // 引擎结束时不无条件复位，而是恢复进入前的状态，避免内层 finally 把编排期间
        // 的 running 提前置 false，导致用户在编排执行中再发消息绕过并发守卫。
        boolean wasRunning = state.running();
        state.running(true);
        AgentEventPublisher publisher = stateManager.publisher();
        // 用户在界面选定的模型端点（未指定时由路由回退主端点）
        String modelId = modelIdOf(ctx);
        // 台账 #26：进入本轮前的会话消息基线。RetryModelGateway 的重试单元是「整轮 run」，
        // 失败重跑时若不回滚，上一轮已落库的 assistant/tool 消息会再追加一遍 → 上下文与
        // token 成倍膨胀（最坏 3x），模型还会看到自相矛盾的重复历史。
        // 这里记录基线，异常路径回滚到进入前；成功路径不动。
        // 局限（已在台账标注）：文件写入、检查点等副作用无法回滚，根治需把重试下沉到单次模型调用。
        int messageBaseline = state.messages().size();
        try {
            String sysPrompt = assembleSystemPrompt(ctx, phase, state);
            List<ToolSpecification> tools = toolGateway.buildToolSpecifications(ctx.workspaceId(), ctx.goal());

            // 步数上限接线：PLAN/ACT 复用同一引擎，仅终止条件不同（StepLimitGuard 统一裁决）。
            // ACT 阶段优先取 Agent 预设中的 maxSteps（设置页可调、保存即生效），PLAN 保持 yml 口径。
            int maxSteps = phase == Phase.ACT ? presetResolver.actMaxSteps() : stepLimitGuard.maxSteps(phase);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(sysPrompt));
            List<ChatMessage> history = new ArrayList<>(state.messages());
            if (history.isEmpty()) {
                history.add(UserMessage.from(goal == null ? ctx.goal() : goal));
            }
            messages.addAll(history);

            // 上下文压缩：占用率 → 阈值自动压缩（阈值触发，不等定时任务）
            int historySize = messages.size() - 1; // 不含系统提示的历史条数
            messages = maybeCompact(ctx, messages, publisher);
            // P1-3 fix：压缩产物回写会话状态（历史部分，仅过滤掉系统提示），否则下一轮
            // 从 state 重新读全量历史 → 每轮重复压缩、状态与模型所见上下文永久分叉
            List<ChatMessage> compactedHistory = messages.stream()
                    .filter(m -> !(m instanceof SystemMessage))
                    .toList();
            if (compactedHistory.size() < historySize) {
                state.replaceMessages(new ArrayList<>(compactedHistory));
            }

            publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(), "开始" + phase.name() + "阶段"));

            // 流式主路径 + 同步回退：同一路由解析，流式不可用/失败时逐轮降级同步，保证任何端点都能跑通
            StreamingChatModel streaming = modelRouter.resolveStreaming(modelId);
            ChatModel sync = modelRouter.resolve(modelId);

            int step = 0;
            // 规划阶段累计「模型仍在调工具」的轮数：达到上限即放弃本轮规划（见工具闸注释）
            int planToolTurns = 0;
            String finalText = null;
            long tokenUsed = 0;
            // 上一轮输入侧 token（增量统计：每轮只计新增消息，避免全量历史平方级虚高）
            long lastInput = 0;
            // 末次模型响应：落库时需保留 thinking（推理模型要求下一轮原样回传 reasoning_content）
            AiMessage lastAi = null;
            while (step < maxSteps) {
                // 用户已取消：立即停止本轮（不再开启新的一轮模型调用/工具分发）
                if (state.cancelRequested()) {
                    log.info("引擎运行被用户取消：session={} phase={}", ctx.sessionId(), phase);
                    return EngineRunResult.of(ctx.sessionId(), phase, finalText, tokenUsed,
                            modelRouter.modelName(modelId), "cancelled");
                }
                step++;
                AiMessage ai;
                TurnOutcome outcome;
                try {
                    // 思考链回传契约归一化（案例 K 残余）：推理模型要求「请求携带 tools」时历史所有
                    // assistant 消息原样带 reasoning_content，否则 HTTP 400。若某轮模型未产出思考，
                    // 该消息与其余带思考消息不一致 —— 此处对「发往模型」的副本做归一化（不改内存态/落盘）：
                    // 纯文本无思考 assistant 剔除；含 tool_calls 的无思考 assistant 补最小占位以保住配对。
                    ChatRequest request = ChatRequest.builder()
                            .messages(ThinkingConsistency.normalizeForThinkingMode(messages))
                            .toolSpecifications(tools)
                            .build();
                    // 流式：整轮实时推送正文增量，工具请求在完成回调中返回；同步：整段返回后伪流式补齐。
                    // PLAN 阶段不推正文增量：该阶段的输出是「计划文本」，只被 Orchestrator 解析
                    // （PlanGenerator）后丢弃，从不作为回答展示；推给前端会让用户看到同一答案
                    // 重复 2~3 次（首轮计划 + 重规划 + ACT），CLI 与 Web 均复现。
                    boolean emitDeltas = phase != Phase.PLAN;
                    outcome = streaming != null
                            ? streamTurn(ctx, request, publisher, streaming, sync, emitDeltas)
                            : syncTurn(ctx, request, publisher, sync, "", true, emitDeltas);
                    ai = outcome.ai();
                } catch (Exception e) {
                    throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
                }
                // 真实指标：每次模型调用计 1；token 用量优先取模型响应携带的真实 TokenUsage，
                // 仅在流式端点不下发用量时（usage=null）回退本地估算，杜绝「虚假 token」统计
                metricsCollector.reportModelCall(ctx.sessionId());
                TokenUsage realUsage = outcome.tokenUsage();
                long inputTokens = realUsage != null && realUsage.inputTokenCount() != null
                        ? realUsage.inputTokenCount() : tokenMeter.count(messages);
                long outputTokens = realUsage != null && realUsage.outputTokenCount() != null
                        ? realUsage.outputTokenCount() : tokenMeter.count(ai);
                long callTokens = Math.max(0, inputTokens - lastInput) + outputTokens;
                lastInput = inputTokens;
                tokenUsed += callTokens;
                publisher.publish(ctx.sessionId(), AgentEvent.token(
                        ctx.sessionId(), callTokens, inputTokens, outputTokens,
                        modelRouter.contextWindow(modelId),
                        modelRouter.modelName(modelId), false));
                messages.add(ai);
                lastAi = ai;

                // 思考（reasoning/thinking）已按段落实时推送（流式）或随同步轮发布（syncTurn），
                // 这里不再整段补发，避免与分段思考块重复。

                // 无工具调用 → 最终回复（正文已在生成时实时推送，这里仅取文本收尾）
                if (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
                    finalText = outcome.text();
                    break;
                }

                // 有工具调用 → 批量分发（只读并行、写串行），按输入顺序回灌结果。
                // 模型本轮的过程叙述已实时流入正文（ChatGPT 式：可见文本统一进消息体），不再单独进思考区。
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> reqs = ai.toolExecutionRequests();
                // 分发前再查一次取消（模型返回后、执行工具前用户取消则不再改文件）
                if (state.cancelRequested()) {
                    log.info("引擎工具分发前被取消：session={} phase={}", ctx.sessionId(), phase);
                    return EngineRunResult.of(ctx.sessionId(), phase, finalText, tokenUsed,
                            modelRouter.modelName(modelId), "cancelled");
                }
                // 规划阶段工具闸：PLAN 的产物只有「计划文本」，不需要任何工具。
                // 但工具定义必须照常下发 —— 推理模型要求「请求携带 tools」时历史 assistant 消息
                // 原样带 reasoning_content（见 ThinkingConsistency），撤掉 tools 反而 400。
                // 于是在分发前拦截：不执行、不发 action/tool_result（否则前端会把一次目录穷举
                // 渲染成一屏「查看的文件」），只回灌一条拒绝；连续两轮仍在调工具即判定为穷举，
                // 直接退出规划。此前无此闸：模型在 PLAN 里逐个目录 file.list，12 步全耗在列举上，
                // 最终以「已达最大步数」收场（实测一次 PLAN 就产生 52 条目录查询）。
                if (phase == Phase.PLAN) {
                    boolean giveUp = ++planToolTurns >= PLAN_TOOL_TURN_LIMIT;
                    for (ToolExecutionRequest req : reqs) {
                        messages.add(ToolExecutionResultMessage.from(
                                req.id(), req.name(), PLAN_TOOL_DENIED));
                    }
                    if (giveUp) {
                        log.info("规划阶段连续 {} 轮调用工具，判定为穷举，放弃本轮规划：session={}",
                                planToolTurns, ctx.sessionId());
                        break;
                    }
                    publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(),
                            "【分析】规划阶段无需执行操作，已驳回工具调用，继续产出计划。"));
                    continue;
                }
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
                    // 成败标志与错误文案如实取自 ToolResult：此前恒传 ok=true / error=null，
                    // 会让 SSE、CLI 与指标把工具失败记成成功（对模型可见、对人不诚实）。
                    publisher.publish(ctx.sessionId(), toolResultEvent(ctx.sessionId(), req.id(),
                            req.name(), toolResult, resultText, MAX_TOOL_RESULT_TEXT));
                    messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), resultText == null ? "" : resultText));
                }
            }

            // 步数耗尽但无正文：PLAN 阶段不是致命错误（编排层会降级为「无需拆分，直接执行」），
            // ACT/ASK 则先做一次「无工具收尾」把已收集到的信息收成结论，仍无产出才如实报错。
            if (finalText == null || finalText.isBlank()) {
                if (phase == Phase.PLAN) {
                    // 此前 PLAN 无产出也发 error 事件 → 用户看到红色「已达最大步数」，而编排层
                    // 其实继续执行了 → 界面上挂着一条骗人的失败提示。这里改为可归因的进度提示。
                    log.warn("规划阶段未产出计划：session={} steps={} toolTurns={}",
                            ctx.sessionId(), step, planToolTurns);
                    publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(),
                            "【分析】规划阶段未产出结构化计划（已用 " + step + " 步），转为直接执行。"));
                    return new EngineRunResult(ctx.sessionId(), phase, "", tokenUsed,
                            modelRouter.modelName(modelId), "plan-empty", null);
                }
                // 步数用尽的「止损产出」：本轮已经读/列了不少东西，却因为没产出正文就整轮丢弃，
                // 用户拿到的是「一无所有 + 一条报错」。这里禁用工具再要一次正文（一次调用），
                // 把已有信息收成结论；模型仍无正文则按原样报错（不伪造结论）。
                publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(),
                        "【安全阀】步数用尽，正在汇总本轮已获得的信息…"));
                Salvage salvage = salvageConclusion(ctx, messages, streaming, sync, publisher, lastInput);
                if (salvage != null) {
                    finalText = salvage.text();
                    lastAi = salvage.ai();
                    tokenUsed += salvage.tokens();
                }
            }

            if (finalText != null && !finalText.isBlank()) {
                // 条件选择（选项块）：模型按约定输出 ```options 受控 JSON 块时，
                // 剥离该块（用户只看说明文字）、发布 options 事件并挂起，等待用户选择后继续。
                Optional<OptionsBlockParser.Parsed> picked = OptionsBlockParser.parse(finalText);
                if (picked.isPresent()) {
                    OptionsBlockParser.Parsed parsed = picked.get();
                    String stripped = parsed.strippedBody();
                    if (stripped != null && !stripped.isBlank()) {
                        state.appendMessage(lastAi == null
                                ? AiMessage.from(stripped)
                                : lastAi.toBuilder().text(stripped).build());
                    }
                    publisher.publish(ctx.sessionId(), AgentEvent.options(
                            ctx.sessionId(), parsed.question(), parsed.options(),
                            parsed.allowCustom(), parsed.customHint(),
                            presetResolver.optionsTimeoutSec()));
                    log.info("条件选择挂起：session={} 选项数={}", ctx.sessionId(), parsed.options().size());
                    return EngineRunResult.of(ctx.sessionId(), phase, stripped == null ? "" : stripped,
                            tokenUsed, modelRouter.modelName(modelId), "options");
                }
                // 落库时保留 thinking：推理模型（deepseek-reasoner 等）要求下一轮把
                // reasoning_content 原样回传，用 AiMessage.from(text) 重建会丢掉思考链，
                // 导致下一轮请求被模型厂商拒绝。
                state.appendMessage(lastAi == null
                        ? AiMessage.from(finalText)
                        : lastAi.toBuilder().text(finalText).build());
            } else {
                // 台账 #17：步数耗尽（或模型始终没产出正文）时，此前既不发 error、也不落库，
                // 却返回 status="success" —— 用户看到一条空白回答，日志全绿，无法归因。
                // 现在如实报错：错误事件 + status="max-steps" + error 文案（会话层据此返回 ERROR）。
                // 走到这里的只剩「连无工具收尾也拿不到正文」的情形（见上面的 Salvage）。
                String reason = "已达最大步数（" + maxSteps + "）仍未产出结论，本轮未完成，请调整目标或拆分任务后重试";
                log.warn("引擎步数耗尽：session={} phase={} steps={}", ctx.sessionId(), phase, step);
                publisher.publish(ctx.sessionId(), AgentEvent.error(
                        ctx.sessionId(), phase.name(), null, reason, "错误:ASK"));
                return new EngineRunResult(ctx.sessionId(), phase, "", tokenUsed,
                        modelRouter.modelName(modelId), "max-steps", reason);
            }
            // 编排模式（多步连续 run）下不逐次发布 stop，避免前端误将中间步骤当整轮结束而断连；
            // 收尾 stop 由编排层/会话层统一发布（单步运行时照常发布）。
            if (!isSuppressStop(ctx)) {
                publisher.publish(ctx.sessionId(), AgentEvent.stop(ctx.sessionId(), "success", finalText));
            }
            return EngineRunResult.of(ctx.sessionId(), phase, finalText, tokenUsed, modelRouter.modelName(modelId), "success");
        } catch (Exception e) {
            log.error("引擎运行失败：session={} phase={}", ctx.sessionId(), phase, e);
            // 台账 #26：把本轮回滚到进入前的消息基线，避免上层整轮重试时重复累积历史。
            rollbackToBaseline(state, messageBaseline);
            publisher.publish(ctx.sessionId(), AgentEvent.error(
                    ctx.sessionId(), phase.name(), null, e.getMessage(), "错误:ASK"));
            return EngineRunResult.error(ctx.sessionId(), phase, e.getMessage());
        } finally {
            state.running(wasRunning);
        }
    }

    /**
     * 步数耗尽的「止损产出」：禁用工具再要一次正文，把本轮已读/已列到的信息收成结论。
     *
     * <p>为什么需要：步数用尽时此前直接丢整轮上下文并报错，用户看到的只有
     * 「已达最大步数」—— 而这一轮其实已经拿到了大量事实（目录结构、文件内容、命令输出）。
     * 用一次不带工具的调用把这些事实总结成答案，比「一无所有 + 一条红色报错」有用得多。</p>
     *
     * <p>约束：只有一次机会，且请求不带任何工具（模型无法再发起探索）；模型仍无正文时返回
     * {@code null}，由调用方按原样如实报错，绝不伪造结论。任何异常同样降级为 {@code null}。</p>
     *
     * @param messages  当前完整消息（含本轮全部工具结果）
     * @param lastInput 上一次调用的输入 token（用于增量计费，与主循环口径一致）
     * @return 收尾结果（文本 + AiMessage + 本次消耗）；失败返回 null
     */
    private Salvage salvageConclusion(ConversationCtx ctx, List<ChatMessage> messages,
                                      StreamingChatModel streaming, ChatModel sync,
                                      AgentEventPublisher publisher, long lastInput) {
        try {
            List<ChatMessage> finalMessages = new ArrayList<>(messages);
            finalMessages.add(UserMessage.from(
                    "你已用完本轮的步数预算，不能再调用任何工具（本轮对话也不再提供工具）。"
                            + "请立刻基于上面已经获得的信息，用文字给出最终结论："
                            + "已经查清/完成了什么、还没查清什么、建议的下一步是什么。"
                            + "不要道歉、不要复述过程、不要请求继续。"));
            ChatRequest request = ChatRequest.builder()
                    .messages(ThinkingConsistency.normalizeForThinkingMode(finalMessages))
                    .build();
            TurnOutcome out = streaming != null
                    ? streamTurn(ctx, request, publisher, streaming, sync, true)
                    : syncTurn(ctx, request, publisher, sync, "", true, true);
            String text = out.text();
            if (text == null || text.isBlank()) {
                return null;
            }
            metricsCollector.reportModelCall(ctx.sessionId());
            TokenUsage usage = out.tokenUsage();
            long inputTokens = usage != null && usage.inputTokenCount() != null
                    ? usage.inputTokenCount() : tokenMeter.count(finalMessages);
            long outputTokens = usage != null && usage.outputTokenCount() != null
                    ? usage.outputTokenCount() : tokenMeter.count(out.ai());
            long callTokens = Math.max(0, inputTokens - lastInput) + outputTokens;
            publisher.publish(ctx.sessionId(), AgentEvent.token(
                    ctx.sessionId(), callTokens, inputTokens, outputTokens,
                    modelRouter.contextWindow(modelIdOf(ctx)),
                    modelRouter.modelName(modelIdOf(ctx)), false));
            return new Salvage(text, out.ai(), callTokens);
        } catch (Exception e) {
            log.warn("步数耗尽收尾调用失败（按原错误返回）：session={}", ctx.sessionId(), e);
            return null;
        }
    }

    /** 收尾产出：正文 + 该次调用的 AiMessage（保留 thinking，供下一轮推理模型回传）+ 消耗。 */
    private record Salvage(String text, AiMessage ai, long tokens) {
    }

    /**
     * 回滚本轮新增的会话消息（台账 #26，配合「整轮重试」使用）。
     *
     * <p>只回滚<b>消息</b>：已经发生的文件写入/检查点无法撤销（这是重试粒度问题的残留），
     * 但至少保证重试不会让上下文与 token 成倍膨胀。</p>
     */
    private static void rollbackToBaseline(ConversationStateManager.SessionState state, int baseline) {
        try {
            List<ChatMessage> msgs = state.messages();
            // CopyOnWriteArrayList：按索引尾部删除，避免整表替换与并发读冲突
            while (msgs.size() > baseline) {
                msgs.remove(msgs.size() - 1);
            }
        } catch (RuntimeException e) {
            log.warn("回滚本轮消息失败（不影响错误上报）：baseline={}", baseline, e);
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
        // 压缩阈值优先取 Agent 预设（设置页可调），未配置时回退 yml 默认。
        double threshold = presetResolver.contextThreshold();
        long budget = (long) (window * threshold);
        // 注意：这里不再发布 token 事件（P2-4）。该估算值是「全量历史」，并非本次模型
        // 调用的增量消耗，若按 used 上报会导致会话级 token 虚高（每轮重复累计全量）。
        // 真实 token 由每次模型调用处按增量上报。
        if (estimatedTokens < budget) {
            return messages;
        }
        log.info("上下文接近上限：{} tokens / {}，触发压缩", estimatedTokens, window);
        publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(), "上下文接近上限，正在压缩（保留关键结果）"));
        List<ChatMessage> compacted = compactionPipeline.compact(messages, Map.of("threshold", threshold));
        return compacted == null ? messages : compacted;
    }

    /**
     * 五层提示词组装：基座 + 人格 + 阶段指令 + 权限约束 + 召回上下文（静态/动态分界）。
     *
     * <p>基座层不硬编码：取自 {@code <frameworkRoot>/LUCKY.md}（{@link BasePromptStore}），
     * 用户改完保存即生效。每次 run 只读一次小文件，不做进程内缓存，避免改完不生效。</p>
     */
    private String assembleSystemPrompt(ConversationCtx ctx, Phase phase,
                                        ConversationStateManager.SessionState state) {
        SystemPromptAssembler assembler = new SystemPromptAssembler();
        assembler.base(basePromptStore.basePrompt());
        assembler.persona(personaService.renderPersonaLayer(personaId(ctx)));
        // 自定义系统提示词（Agent 预设，设置页可编辑）：置于基座与人格之后、阶段/权限之前，
        // 让用户可以「补丁式」追加输出风格与硬性要求，而无需改动框架自带的 LUCKY.md 基座。
        String customPrompt = presetResolver.systemPrompt();
        if (customPrompt != null) {
            assembler.memory("【自定义指令】\n" + customPrompt);
        }
        assembler.phase(phaseInstruction(phase));
        assembler.permission(permissionInstruction(ctx.permissionLevel()));
        // 当前工作空间上下文（核心）：告诉 Agent 它到底在哪个工作区、根目录在哪，
        // 避免「以为工作在默认目录/不知道项目在哪」而反复向用户索要路径（用户说“项目”即指此目录）。
        Optional<Workspace> ws = workspaceConfig.getWorkspace(ctx.workspaceId());
        if (ws.isPresent() && ws.get().path() != null && !ws.get().path().isBlank()) {
            assembler.memory("【当前工作空间】\n"
                    + "名称：" + (ws.get().name() == null ? ctx.workspaceId() : ws.get().name()) + "\n"
                    + "根目录：" + ws.get().path() + "\n"
                    + "用户所说的“项目”“工作目录”“这里”等，若未特别说明，均指这个根目录；"
                    + "所有文件操作（列目录/读/写）应基于此根目录进行，不要向用户索要路径，"
                    + "也不要访问根目录之外的路径。");
            // 多条规则注入（§五-5-1）：全局规则（框架根 LUCKY.md 的 ## 分节）+ 项目规则
            // （工作空间 LUCKY.md 的 ## 分节），项目优先级更高；仅注入启用项。
            // 未分节的旧版 LUCKY.md 由 RuleStore 兼容为「整文件单条规则」，行为与改造前一致。
            try {
                String rules = ruleStore.renderForPrompt(ws.get().path());
                if (rules != null && !rules.isBlank()) {
                    assembler.memory(rules.trim());
                }
            } catch (Exception e) {
                log.warn("读取规则失败（忽略）：ws={}", ctx.workspaceId(), e);
            }
        }
        // 记忆召回：md 轨（索引+预取 或 全量兜底）+ JSONL 按工作空间补充（P1-7/1-8）
        String mdMemory = buildMdMemory(ctx, state);
        RecallResult recall = memoryRetriever.recall(ctx.userId(), ctx.workspaceId(),
                ctx.goal() == null ? "" : ctx.goal(), 5);
        StringBuilder memory = new StringBuilder();
        if (mdMemory != null && !mdMemory.isBlank()) {
            memory.append(mdMemory);
        }
        if (recall != null && recall.entries() != null && !recall.entries().isEmpty()) {
            if (!memory.isEmpty()) {
                memory.append('\n');
            }
            memory.append("### 补充记忆\n");
            recall.entries().forEach(e -> memory.append("- [").append(e.track().code())
                    .append("] ").append(e.content()).append('\n'));
        }
        if (!memory.isEmpty()) {
            assembler.memory("【已知记忆】\n" + memory.toString().trim());
        }
        return assembler.assemble();
    }

    /**
     * md 轨记忆注入：
     * <ul>
     *   <li>预取开启（默认 true）：两级 MEMORY.md 索引全量注入 + 选择器挑出的相关条目注入
     *       （会话内去重）；选择器失败/无命中时回退「仅索引 + 提示可用记忆工具」；</li>
     *   <li>预取关闭：保留旧版全文召回（整体→项目→会话）。</li>
     * </ul>
     */
    private String buildMdMemory(ConversationCtx ctx, ConversationStateManager.SessionState state) {
        if (!memoryMdProps.prefetchEnabled()) {
            return hierarchyMemoryRetriever.recallText(ctx.workspaceId(), ctx.sessionRef().sessionId());
        }
        String indexText = hierarchyMemoryRetriever.recallIndex(ctx.workspaceId());
        String entries = memoryPrefetcher.prefetch(ctx, state);
        StringBuilder sb = new StringBuilder();
        if (indexText != null && !indexText.isBlank()) {
            sb.append(indexText);
        }
        if (entries != null && !entries.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("### 相关记忆条目\n").append(entries);
        } else if (sb.length() > 0) {
            sb.append("\n（以上为记忆索引；需要详细内容时可用记忆读取工具 memory.read / memory.search）");
        }
        return sb.toString();
    }

    private String personaId(ConversationCtx ctx) {
        Object personaId = ctx.extra().get("personaId");
        return personaId == null ? null : String.valueOf(personaId);
    }

    private String phaseInstruction(Phase phase) {
        return switch (phase) {
            case PLAN -> """
                    【阶段指令】当前为规划阶段：先判断任务复杂度，产出结构化计划。
                    本阶段不执行任何操作是框架约束（工具会被驳回），**不要向用户解释或抱怨这一点**，
                    也不要因此声称「无法完成任务」——执行由下一步负责，你只需给出计划。
                    - 若为简单任务（无需工具拆分、一次问答即可完成）：不要产出步骤列表，直接输出结构化总结（结论 + 关键信息），一步到位。
                    - 若为复杂任务：产出结构化计划 JSON（goal/steps/canAutoExecute），不要执行任何操作。
                    每个步骤尽可能声明 verify 字段，说明「如何客观证明这一步做成了」：
                    - 产物类步骤用 {"type":"file","path":"产物相对路径","contains":"关键内容片段"}；
                    - 可校验步骤用 {"type":"command","command":"校验命令","contains":"期望输出"}，\
                    校验命令按退出码判定（构建/测试/接口状态码/数据库查询均可），仅在全部权限下执行；
                    无法客观校验的步骤省略 verify，交由整体判定。
                    关于拆解方式，二选一或并存：
                    1) 常规拆分：若问题可拆成同属你职责的连续步骤，用 "steps" 数组给出 id/type/desc/target/safe/verify；
                       type 取 file / shell / ask / tool 之一（仅用于说明该步性质，不写也能执行）；
                    2) 隔离子代理：仅当任务含多个互相独立、可并行/需隔离上下文的高复杂度子问题（如分别评审多模块、
                       分别生成多份独立产物）时，用顶层 "subagents" 数组声明，每项 {"id","name","task","tools","permissionMode"}，
                       框架会为每项启动一个隔离子代理执行并回传摘要；不要把本可由主链路步骤完成的普通拆解放进 subagents。
                    当拆分与子代理并存时，subagents 优先执行（先并行解决独立子问题），随后再用 steps 完成串行收尾。
                    子代理不可提升权限、只回摘要；不要在 subagents.task 里要求它去做需要整库上下文才能决策的事。""";
            case ACT -> """
                    【阶段指令】当前为执行阶段。严格遵守：
                    1. 只做用户明确要求的事：不执行用户未要求的操作，不擅自修改/删除/生成目标之外的文件，不自行扩大任务范围；
                       需要额外操作时先停下来询问用户，得到同意后再做。
                    2. 先判断任务复杂度：
                       - 简单任务（无需工具或一次即可完成）：直接输出【结构化总结】（结论 + 做了什么 + 关键路径），
                         不要拆步骤、不要反复复评、不要冗余过程。
                       - 复杂任务：按步骤执行，每完成一步先用简短的 Markdown 段落向用户阶段性汇报该步结果
                         （做了什么、结果如何），不要一次把所有过程写完；全部步骤完成后，输出【总结】概括完成的操作、
                         修改/新增的文件路径与最终结论。
                    3. 阶段性汇报要简短，总结要完整；工具调用过程中不要长篇输出过程细节。
                    4. 任务完成后立即停止：不要继续追加无关内容、不要重复确认已经完成的事项。
                    5. 模糊输入必须确认：当用户消息是单个字、单个数字或极短内容且可能对应多个解释
                       （如选项编号、历史待办等）时，禁止自行猜测为一个选项并开始执行，必须先用一句话确认用户意图，
                       得到用户明确回复后再执行。
                    总结必须使用 Markdown 格式输出（可用标题、无序/有序列表、加粗、行内代码、代码块、引用等标准 Markdown 语法）。
                    6. 需要用户在多个方案中做选择时，用受控选项块表达，不要在正文里直接罗列后自问自答：
                       先写一两句说明，然后输出一个 ```options 代码块（JSON），不要把选项写成普通列表。
                       例如：
                       ```options
                       {"question":"选择实现方案","options":[{"id":"1","label":"方案A：…","detail":"…"},{"id":"2","label":"方案B：…","detail":"…","recommended":true}],"allowCustom":true,"customHint":"其他（请补充说明）"}
                       ```
                       规则：options 为 2~4 项；每项须有 id 与 label；推荐的方案加 "recommended": true；
                       允许用户补充其他想法时置 "allowCustom": true（默认开启）。用户选择后对话会自动继续。""";
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

    /**
     * 工具结果事件：成败标志与错误文案如实上报（D18「错误即数据」）。
     *
     * <p>{@code ok} 取自 {@link ToolResult#ok()}，{@code error} 透传结果里的错误文案，
     * 保证 SSE / CLI / MetricsCollector 不再把失败记成成功。{@code suspended}（ASK）
     * 由调用方提前转 ask 事件并 return，不走本方法，避免重复计入成败统计。</p>
     *
     * <p>包可见静态方法：把这段判定从 19 个依赖的引擎实例里剥离，便于单测直接覆盖，
     * 不需要构造整个执行上下文。</p>
     */
    static AgentEvent toolResultEvent(String sessionId, String callId, String toolName,
                                      ToolResult result, String resultText, int maxText) {
        boolean ok = result.ok();
        // 失败且无错误文案时不能回退成 "ok"，否则摘要本身仍在误导。
        String summary = resultText == null || resultText.isBlank()
                ? (ok ? "ok" : "error")
                : truncate(resultText, maxText);
        return AgentEvent.toolResult(sessionId, callId, toolName, ok, summary,
                null, result.isError() ? result.error() : null);
    }

    private static String truncate(String text, int max) {
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

    /** {@code null} 与空白一视同仁（思考链可能为 null，直接 isBlank 会 NPE）。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 单轮模型调用结果：{@code streamed=true} 表示正文已由流式实时推送（同步回退为 false）。
     *  {@code tokenUsage} 为模型响应携带的真实用量（部分流式端点不下发时为 null，回退估算）。 */
    private record TurnOutcome(String text, String thinking, AiMessage ai, boolean streamed,
                               TokenUsage tokenUsage) {
    }

    /**
     * 流式单轮调用（真实流式核心）：经 {@link StreamingChatModel} 把模型产出的每个文本增量
     * 实时发布 {@code CONTENT_DELTA} 事件，前端逐字渲染（真正边生成边显示）；工具请求在
     * {@code onCompleteResponse} 中随 {@code AiMessage} 返回。
     *
     * <p>思考增量（reasoning/thinking）在轮内缓冲，轮末随 {@code TurnOutcome} 返回，由调用方
     * 以 {@code thought} 事件整段收进思考区——思考区默认收起，逐字渲染对用户感知无增益，避免
     * 思考列表被碎片刷屏。</p>
     *
     * <p>流式不可用、超时或出错时降级 {@link #syncTurn}（同轮重试同步调用），保证任何端点都能跑通；
     * 已实时展示的部分增量不会重复推送（{@code alreadyShown} 前缀判定），避免正文拼接错乱。</p>
     *
     * @param request 本轮请求（含当前上下文与工具集）
     * @param sync    同步回退模型（流式失败时同轮重试）
     * @param emitDeltas 是否把正文增量推给前端；PLAN 阶段传 false（计划文本不对外展示）
     * @return 单轮结果（文本 + 思考 + AiMessage + 是否已流式）
     */
    private TurnOutcome streamTurn(ConversationCtx ctx, ChatRequest request,
                                   AgentEventPublisher publisher,
                                   StreamingChatModel streaming, ChatModel sync,
                                   boolean emitDeltas) {
        StringBuilder textBuf = new StringBuilder();
        StringBuilder thinkBuf = new StringBuilder();
        AtomicReference<AiMessage> aiRef = new AtomicReference<>();
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        // P1-6 fix：超时/失败后置 cancelled，禁止迟到的流式增量继续推送，
        // 避免「降级同步重试」期间旧流又吐出内容导致正文拼接错乱/重复
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean[] shownStreamed = {false};
        CountDownLatch latch = new CountDownLatch(1);
        // P2-6 fix：用户在流式生成中点击「停止」时，增量数据仍在持续产出并推送，
        // 即使 sink 已被移除也会入暂存、被重连订阅补发 → 前端表现为「点了停止还在不断发消息」。
        // 这里把 session 级取消标记也纳入推送门禁，取消后立即停止对外发布。
        java.util.function.BooleanSupplier userCancelled = () ->
                stateManager.find(ctx.sessionId()).map(ConversationStateManager.SessionState::cancelRequested)
                        .orElse(false);
        try {
            streaming.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                    if (cancelled.get() || userCancelled.getAsBoolean()
                            || partial == null || partial.text() == null || partial.text().isEmpty()) {
                        return;
                    }
                    shownStreamed[0] = true;
                    textBuf.append(partial.text());
                    // 取消后不再累积/推送：判断置于条件内，确保取消瞬间即止
                    if (userCancelled.getAsBoolean()) {
                        return;
                    }
                    // PLAN 阶段只累积正文供解析，不推给前端（见 execute 中 emitDeltas 注释）
                    if (emitDeltas) {
                        publisher.publish(ctx.sessionId(), AgentEvent.contentDelta(ctx.sessionId(), partial.text()));
                    }
                }

                @Override
                public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
                    if (cancelled.get() || userCancelled.getAsBoolean()
                            || thinking == null || thinking.text() == null || thinking.text().isEmpty()) {
                        return;
                    }
                    // 思考整段缓冲，轮末一次性发布为一个 thought（一次 LLM 思考 = +1，
                    // 避免按段落推送导致「思考次数」被碎片刷爆，前端的思考计数与模型思考次数一一对应）
                    thinkBuf.append(thinking.text());
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    aiRef.set(response.aiMessage());
                    // 台账 #16：流式响应同样携带真实 token 用量，此前从不写入 usageRef
                    // → outcome.tokenUsage() 恒 null → 全链路退回本地估算（中文按 1 字 1 token），
                    // 预算熔断、/tokens、Metrics 全建立在估算值上。
                    if (response != null) {
                        usageRef.set(response.tokenUsage());
                    }
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });
            if (!latch.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("流式调用超时（>" + CALL_TIMEOUT_SECONDS + "s）");
            }
        } catch (Exception e) {
            // 无论超时还是异常：不再接收迟到的流式增量，保证回退路径的正文唯一
            cancelled.set(true);
            errorRef.set(e);
        }
        if (errorRef.get() != null) {
            log.warn("流式调用失败，降级同步：session={} error={}", ctx.sessionId(), errorRef.get().getMessage());
            // 已实时展示的思考/正文不重复推送（降级路径不再发布 thinking）
            return syncTurn(ctx, request, publisher, sync, textBuf.toString(), false, emitDeltas);
        }
        AiMessage ai = aiRef.get();
        if (ai == null) {
            log.warn("流式调用无响应，降级同步：session={}", ctx.sessionId());
            return syncTurn(ctx, request, publisher, sync, textBuf.toString(), false, emitDeltas);
        }
        // 收尾：把未到换行结尾的思考残量作为最后一个段落发布
        String thinkTail = thinkBuf.toString().trim();
        if (!thinkTail.isBlank()) {
            publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), thinkTail));
        }
        String text = textBuf.length() > 0 ? textBuf.toString() : (ai.text() == null ? "" : ai.text());
        if (textBuf.isEmpty() && !text.isBlank()) {
            // 端点未下发增量（整段返回）：伪流式补齐，保证界面仍有逐字输出效果
            streamChunked(ctx, text, publisher, emitDeltas);
        }
        String thinking = thinkBuf.length() > 0 ? thinkBuf.toString() : thinkingOf(ai);
        // 思考链回传（必须补写，勿删）：推理模型在请求携带 tools 时要求历史所有轮 assistant 消息
        // 原样带上 reasoning_content，缺失即 HTTP 400（The `reasoning_content` in the thinking mode
        // must be passed back to the API.）。流式路径下思考是经 onPartialThinking 增量回调送达的，
        // 框架收尾构造的 AiMessage 带回的是 null（实测），于是「上下文里的 assistant 消息」
        // 缺 reasoning_content，下一次调用（同轮 ACT、或下一轮 PLAN）带 tools 就被厂商直接拒绝。
        // 这里把已缓冲的思考补写到 AiMessage 上，让「写回会话的消息」与「已展示的思考」一致；
        // 只补写「框架没给」的情形，不覆盖框架已给的值。thinking 可能为 null，判空不可省。
        if (isBlank(ai.thinking()) && !isBlank(thinking)) {
            ai = ai.toBuilder().thinking(thinking).build();
        }
        return new TurnOutcome(text, thinking, ai, shownStreamed[0], usageRef.get());
    }

    /**
     * 同步单轮调用（流式不可用/失败时的回退）：整段返回后伪流式推送到正文，保证界面始终有
     * 逐字输出效果；已实时展示的部分（{@code alreadyShown}）不重复推送，避免正文拼接错乱。
     *
     * @param publishThinking 是否发布思考块：直接同步调用时为 true；流式降级路径为 false
     *                        （思考已在降级前按段落实时推送过，避免重复）
     */
    private TurnOutcome syncTurn(ConversationCtx ctx, ChatRequest request,
                                 AgentEventPublisher publisher, ChatModel sync, String alreadyShown,
                                 boolean publishThinking, boolean emitDeltas) {
        ChatResponse response = sync.chat(request);
        TokenUsage usage = response.tokenUsage();
        AiMessage ai = response.aiMessage();
        String full = ai.text() == null ? "" : ai.text();
        if (full.startsWith(alreadyShown) && full.length() > alreadyShown.length()) {
            // 已展示增量是同步文本前缀：仅补齐剩余，保证完整答案不被截断
            streamChunked(ctx, full.substring(alreadyShown.length()), publisher, emitDeltas);
        } else if (alreadyShown.isEmpty() && !full.isBlank()) {
            streamChunked(ctx, full, publisher, emitDeltas);
        } else if (!full.isBlank()) {
            // P1-6 fix：已展示的增量与重试结果不一致（超时后重试文本通常不同）时，
            // 不能再静默丢文本（否则流式展示 ≠ 持久化内容）；先提示后完整输出，
            // 保证用户看到完整最终回答，且与落库结果一致
            publisher.publish(ctx.sessionId(), AgentEvent.progress(ctx.sessionId(),
                    "前序输出发生中断，正在重新生成完整回答…"));
            streamChunked(ctx, full, publisher, emitDeltas);
        }
        if (publishThinking) {
            String thinking = thinkingOf(ai);
            if (thinking != null && !thinking.isBlank()) {
                publisher.publish(ctx.sessionId(), AgentEvent.thought(ctx.sessionId(), thinking));
            }
        }
        return new TurnOutcome(full, thinkingOf(ai), ai, false, usage);
    }

    /**
     * 伪流式补齐：将已生成的文本按小块切分，逐块发布 {@code CONTENT_DELTA} 事件，前端渲染出
     * 打字机效果。仅当端点不支持下发流式增量时使用（真实流式见 {@link #streamTurn}）。
     */
    private String streamChunked(ConversationCtx ctx, String text, AgentEventPublisher publisher,
                                 boolean emitDeltas) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int chunk = 6;
        for (int i = 0; i < text.length(); i += chunk) {
            String delta = text.substring(i, Math.min(text.length(), i + chunk));
            if (emitDeltas) {
                publisher.publish(ctx.sessionId(), AgentEvent.contentDelta(ctx.sessionId(), delta));
            }
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
