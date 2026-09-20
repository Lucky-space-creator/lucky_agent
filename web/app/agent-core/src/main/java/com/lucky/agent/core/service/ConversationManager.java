package com.lucky.agent.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.dto.HookEvent;
import com.lucky.agent.common.dto.HookEventName;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.repository.SessionRepository;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.executor.api.RollbackService;
import com.lucky.agent.executor.api.dto.Snapshot;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.permission.service.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话编排器：负责 PLAN→ACT 编排、记忆、会话持久化、事件推送与子任务进度。
 * <p>默认为单 Agent：普通对话直接 ACT（REACT 循环内完成规划与执行）；
 * 复杂任务先 PLAN（产出结构化计划，校验失败重规划）再 ACT，并推送任务列表与进度。</p>
 */

@Slf4j
@Service
public class ConversationManager {

    /** 用户选定模型端点在 ctx.extra / UserInput.extra 中的键（与 ChatController 约定一致）。 */
    private static final String MODEL_ID_KEY = "modelId";

    /** 意图解析 JSON 解析复用（ObjectMapper 线程安全）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConversationStateManager stateManager;
    private final MemoryStore memoryStore;
    private final WorkspaceConfig workspaceConfig;
    private final AgentOrchestrator orchestrator;
    private final ModelRouter modelRouter;
    private final LifecycleHookDispatcher hookDispatcher;
    private final SessionRepository sessionRepository;
    private final PermissionService permissionService;
    private final MetricsCollector metricsCollector;
    private final MarkdownMemoryWriter markdownMemoryWriter;
    private final RollbackService rollbackService;
    private final ConcurrentMap<String, Boolean> sessionStarted = new ConcurrentHashMap<>();

    public ConversationManager(ConversationStateManager stateManager,
                               @Qualifier("userMemoryStore") MemoryStore memoryStore,
                               WorkspaceConfig workspaceConfig,
                               AgentOrchestrator orchestrator,
                               ModelRouter modelRouter,
                               LifecycleHookDispatcher hookDispatcher,
                               SessionRepository sessionRepository,
                               PermissionService permissionService,
                               MetricsCollector metricsCollector,
                               MarkdownMemoryWriter markdownMemoryWriter,
                               RollbackService rollbackService) {
        this.stateManager = stateManager;
        this.memoryStore = memoryStore;
        this.workspaceConfig = workspaceConfig;
        this.orchestrator = orchestrator;
        this.modelRouter = modelRouter;
        this.hookDispatcher = hookDispatcher;
        this.sessionRepository = sessionRepository;
        this.permissionService = permissionService;
        this.metricsCollector = metricsCollector;
        this.markdownMemoryWriter = markdownMemoryWriter;
        this.rollbackService = rollbackService;
    }

    /**
     * 提交用户输入并运行（返回的 Mono 完成于运行结束；调用方决定是否等待）。
     *
     * @param ref   会话
     * @param input 用户输入
     * @return 运行结果
     */
    public Mono<RunResult> submit(SessionRef ref, UserInput input) {
        return Mono.fromCallable(() -> execute(ref, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 会话历史（持久化恢复）。*/
    public Mono<SessionSnapshot> history(SessionRef ref) {
        return Mono.fromCallable(() -> {
            SessionSnapshot snap = new SessionSnapshot(ref.sessionId(), ref.userId(), ref.workspaceId());
            snap.messages(sessionRepository.loadMessages(ref.sessionId()));
            return snap;
        });
    }

    /** 会话列表。*/
    public Mono<List<Map<String, Object>>> listSessions(String userId) {
        return Mono.fromCallable(() -> sessionRepository.listByUser(userId).stream()
                .map(this::metaToMap)
                .toList());
    }

    /** 新建空白会话（元数据落盘：前端「新对话」立即持久化，支持多个新会话共存）。 */
    public Mono<Map<String, Object>> createSession(String userId, String workspaceId, String title) {
        return Mono.fromCallable(() -> {
            String sessionId = UUID.randomUUID().toString();
            SessionRef ref = new SessionRef(sessionId, userId, workspaceId);
            sessionRepository.upsertMeta(ref, title == null || title.isBlank() ? "新会话" : title);
            return metaToMap(sessionRepository.loadMeta(sessionId));
        });
    }

    /**
     * 更新会话元数据（重命名 / 切换工作空间）；未提供的字段保持原值。
     *
     * <p>工作空间锁定：已有对话记录的会话不允许切换工作空间（切换会让历史与目录归属错乱），
     * 变更被拒绝并保持原工作空间；空白会话不受限。</p>
     */
    public Mono<Map<String, Object>> updateMeta(SessionRef ref, String title, String workspaceId) {
        return Mono.fromCallable(() -> {
            SessionRepository.SessionMeta existing = sessionRepository.loadMeta(ref.sessionId());
            String effTitle = (title == null || title.isBlank())
                    ? (existing != null && existing.title() != null ? existing.title() : "对话")
                    : title;
            String existingWs = (existing != null && existing.workspaceId() != null && !existing.workspaceId().isBlank())
                    ? existing.workspaceId() : ref.workspaceId();
            String effWs = (workspaceId == null || workspaceId.isBlank()) ? existingWs : workspaceId;
            if (!effWs.equals(existingWs)
                    && !sessionRepository.loadMessages(ref.sessionId()).isEmpty()) {
                // 已有对话记录：拒绝切换工作空间，保持原绑定
                log.warn("拒绝切换工作空间（会话已有对话记录）：session={} oldWs={} newWs={}",
                        ref.sessionId(), existingWs, effWs);
                effWs = existingWs;
            }
            sessionRepository.upsertMeta(new SessionRef(ref.sessionId(), ref.userId(), effWs), effTitle);
            return metaToMap(sessionRepository.loadMeta(ref.sessionId()));
        });
    }

    /**
     * 消息级回溯：还原该消息执行期间创建的全部文件检查点（逆序），
     * 成功后清除消息上的检查点引用（撤销入口随之消失）。
     *
     * @return 成功还原的检查点数量（0 表示无可回溯内容）
     */
    public Mono<Integer> rollbackMessage(String sessionId, String workspaceId, String messageTs) {
        return Mono.fromCallable(() -> {
            if (workspaceId == null || workspaceId.isBlank() || messageTs == null || messageTs.isBlank()) {
                return 0;
            }
            List<SessionSnapshot.MessageRecord> records = sessionRepository.loadMessages(sessionId);
            for (SessionSnapshot.MessageRecord r : records) {
                if (!messageTs.equals(r.ts()) || r.checkpointIds() == null || r.checkpointIds().isEmpty()) {
                    continue;
                }
                int restored = 0;
                List<String> ids = new ArrayList<>(r.checkpointIds());
                for (int i = ids.size() - 1; i >= 0; i--) {
                    Boolean ok = rollbackService.rollback(workspaceId, ids.get(i)).block();
                    if (Boolean.TRUE.equals(ok)) {
                        restored++;
                    }
                }
                if (restored > 0) {
                    sessionRepository.removeCheckpoints(sessionId, messageTs, ids);
                }
                return restored;
            }
            return 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 回滚到消息节点：截断指定消息之后的全部对话上下文（内存态 + 持久化），
     * 保留该消息本身及其之前的全部消息。与「消息级回溯（撤销文件修改）」是两回事。
     *
     * <p>内存态从「截断后的磁盘记录」重建，保证与持久化完全一致，规避其间 tool 类消息、
     * 确认续跑问句（落盘但未进内存态）等造成的索引错位。</p>
     *
     * @return 含 removed（删除条数）与 busy（会话运行中已拒绝）的结果
     */
    public Mono<Map<String, Object>> rollbackToNode(String sessionId, String messageTs) {
        return Mono.fromCallable(() -> {
            if (messageTs == null || messageTs.isBlank()) {
                return Map.of("removed", 0, "busy", false);
            }
            List<SessionSnapshot.MessageRecord> records = sessionRepository.loadMessages(sessionId);
            int diskIndex = -1;
            for (int i = 0; i < records.size(); i++) {
                if (messageTs.equals(records.get(i).ts())) {
                    diskIndex = i;
                    break;
                }
            }
            if (diskIndex < 0) {
                return Map.of("removed", 0, "busy", false);
            }
            // 运行中拒绝：避免与正在进行的事件流/状态回写冲突
            Optional<ConversationStateManager.SessionState> st = stateManager.find(sessionId);
            if (st.isPresent() && st.get().running()) {
                return Map.of("removed", 0, "busy", true);
            }
            int keep = diskIndex + 1;
            int removed = records.size() - keep;
            if (removed <= 0) {
                return Map.of("removed", 0, "busy", false);
            }
            // 内存态从截断后的磁盘记录重建：user/assistant 文本回灌，丢弃瞬态 tool 类消息
            List<SessionSnapshot.MessageRecord> kept = records.subList(0, keep);
            if (st.isPresent()) {
                List<dev.langchain4j.data.message.ChatMessage> rebuilt = new ArrayList<>(kept.size());
                for (SessionSnapshot.MessageRecord r : kept) {
                    if ("user".equals(r.role())) {
                        rebuilt.add(UserMessage.from(r.content()));
                    } else if ("assistant".equals(r.role())) {
                        rebuilt.add(AiMessage.from(r.content()));
                    }
                }
                st.get().replaceMessages(rebuilt);
            }
            sessionRepository.truncateAfter(sessionId, messageTs);
            return Map.of("removed", removed, "busy", false);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 会话元数据 → 列表条目（与 listSessions 共用同一结构）。 */
    private Map<String, Object> metaToMap(SessionRepository.SessionMeta m) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (m == null) {
            return entry;
        }
        entry.put("sessionId", m.sessionId());
        entry.put("userId", m.userId());
        entry.put("workspaceId", m.workspaceId() == null ? "" : m.workspaceId());
        entry.put("title", m.title() == null ? "对话" : m.title());
        entry.put("createdAt", m.createdAt());
        entry.put("updatedAt", m.updatedAt());
        return entry;
    }

    /** 当前工作空间已创建的检查点 ID 集合（供消息级回溯计算增量）。 */
    private Set<String> checkpointIds(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Set.of();
        }
        try {
            return rollbackService.listCheckpoints(workspaceId).stream()
                    .map(Snapshot::checkpointId)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.warn("读取检查点索引失败：wid={} err={}", workspaceId, e.getMessage());
            return Set.of();
        }
    }

    /** 销毁会话（内存 + 持久化 + 事件流 + 指标）。*/
    public boolean destroy(SessionRef ref) {
        sessionStarted.remove(ref.sessionId());
        metricsCollector.stop(ref.sessionId());
        stateManager.destroy(ref.sessionId());
        return sessionRepository.delete(ref.sessionId());
    }

    private RunResult execute(SessionRef ref, UserInput input) {
        String content = input.content() == null ? "" : input.content();
        // 会话绑定的工作空间失效/为空时自动修复为有效工作空间（优先自建，其次内置默认），
        // 并持久化回会话元数据，避免 Agent 实际工作在错误/默认目录而“看不到用户的项目”。
        ref = repairWorkspace(ref);
        // 供 finally 中异步记忆任务捕获（ref 之后不会再变，但需显式 final 方可在 lambda 中引用）
        final SessionRef memoryRef = ref;
        ConversationStateManager.SessionState state = stateManager.session(ref);
        if (state.running()) {
            return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR)
                    .error("会话正在运行中，请等待完成");
        }
        state.running(true);
        // 新一轮提交允许运行：清除上一轮的取消标记（此前被取消的会话可再次执行）
        state.clearCancel();
        AgentEventPublisher publisher = stateManager.publisher();
        // 每轮运行前清空上一轮未消费的暂存事件，避免把上一轮的 stop/error 回放到新一轮（跨轮污染）
        publisher.reset(ref.sessionId());
        try {
            // 指标收集：会话运行期聚合（透明面板数据源）
            metricsCollector.start(ref.sessionId());
            // 首次触发 SessionStart Hook
            if (sessionStarted.putIfAbsent(ref.sessionId(), true) == null) {
                hookDispatcher.dispatch(new HookEvent(HookEventName.SESSION_START, ref.sessionId())
                        .permissionMode(permissionMode(ref)));
            }
            hookDispatcher.dispatch(new HookEvent(HookEventName.USER_PROMPT_SUBMIT, ref.sessionId())
                    .permissionMode(permissionMode(ref)));

            // 用户确认高危操作：一次性放行（确认不是新对话，不追加/持久化用户消息）
            boolean isConfirm = isConfirmRequest(input);
            handleConfirm(ref, input);

            if (!isConfirm) {
                state.appendMessage(UserMessage.from(content));
                sessionRepository.upsertMeta(ref, titleFor(ref, content));
                sessionRepository.appendMessage(ref.sessionId(), "user", content, Instant.now().toString());
                // JSONL 用户轨记忆按工作空间分组落盘（P1-8：跨项目记忆不串扰）
                memoryStore.appendUser(ref.userId(), ref.workspaceId(), content, 0.8, "user");

                // 意图解析（轻量模型调用）：直接同步调用模型解析意图，不经引擎轮，不产生
                // 事件流/stop/会话状态污染（避免解析轮提前发 stop 导致前端截断正文）。
                // 若输入引用了历史上下文（选项/待办/之前的对话）且涉及文件变动 → 先构建询问确认，不直接执行；
                // 若与历史无关 → 以用户当前输入为准，正常执行。
                IntentParse intent = parseIntent(ref, content, modelIdOf(input));
                if (intent != null && !intent.independent() && intent.fileRelated()) {
                    String question = (intent.confirmQuestion() == null || intent.confirmQuestion().isBlank())
                            ? "你刚才的输入似乎引用了之前的上下文，先确认一下：你希望我执行的是「"
                            + (intent.summary() == null || intent.summary().isBlank() ? content : intent.summary())
                            + "」，对吗？"
                            : intent.confirmQuestion();
                    publisher.publish(ref.sessionId(), AgentEvent.progress(ref.sessionId(),
                            "输入引用了历史上下文，先向你确认…"));
                    publisher.publish(ref.sessionId(), AgentEvent.contentDelta(ref.sessionId(), question));
                    sessionRepository.appendMessage(ref.sessionId(), "assistant", question,
                            Instant.now().toString(), null);
                    publisher.publish(ref.sessionId(), AgentEvent.stop(ref.sessionId(), "success", question));
                    return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.SUCCESS).summary(question);
                }
            }

            // 确认续跑时以最近用户目标作为执行目标，避免以空文本重跑
            String goal = isConfirm ? lastUserGoal(state) : content;
            ConversationCtx ctx = buildCtx(ref, goal, modelIdOf(input));
            publisher.publish(ref.sessionId(), AgentEvent.progress(ref.sessionId(),
                    isConfirm ? "已确认，继续执行…" : "收到你的请求：" + content));

            // 统一走编排器：是否拆子任务由 PLAN 阶段模型判断（P2-1，不在代码层做强分流）。
            // 编排模式下引擎不逐次发 stop（suppressStop），由会话层统一收尾发布。
            Set<String> beforeCheckpoints = checkpointIds(ref.workspaceId());
            EngineRunResult result = orchestrator.run(ref, ctx, publisher);
            if (result != null) {
                String reason = result.error() != null
                        ? "error"
                        : (result.status() != null && result.status().equals("ask") ? "ask"
                        : (result.status() != null && result.status().equals("cancelled") ? "cancelled"
                        : "success"));
                publisher.publish(ref.sessionId(), AgentEvent.stop(ref.sessionId(),
                        reason, result.finalText()));
            }

            if (result == null) {
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR).error("引擎无返回结果");
            }
            if ("cancelled".equals(result.status())) {
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR)
                        .error("已取消");
            }
            if (result.finalText() != null && !result.finalText().isBlank()) {
                // 本轮执行期间新产生的文件检查点挂到该条消息上，供消息级回溯（撤销修改过的文件）
                List<String> newCheckpoints = checkpointIds(ref.workspaceId()).stream()
                        .filter(id -> !beforeCheckpoints.contains(id))
                        .toList();
                sessionRepository.appendMessage(ref.sessionId(), "assistant", result.finalText(),
                        Instant.now().toString(), newCheckpoints);
                memoryStore.appendUser(ref.userId(), ref.workspaceId(), result.finalText(), 0.7, "observation");
            }
            if (result.error() != null) {
                // 错误事件已由引擎发出，这里不重复发布
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR).error(result.error());
            }
            return RunResult.of(ref.sessionId())
                    .status(RunResult.RunStatus.SUCCESS)
                    .summary(result.finalText() == null ? "" : result.finalText())
                    .tokenUsed(result.tokenUsed())
                    .model(result.model());
        } catch (Exception e) {
            log.error("会话执行失败：{}", ref.sessionId(), e);
            publisher.publish(ref.sessionId(), AgentEvent.error(
                    ref.sessionId(), "conversation", null, e.getMessage(), "重试"));
            return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR).error(e.getMessage());
        } finally {
            state.running(false);
            hookDispatcher.fire(new HookEvent(HookEventName.STOP, ref.sessionId()));
            // P0-4 fix：会话收尾分层 Markdown 记忆总结改为异步执行，不阻塞 SSE 主流程收尾；
            // 记忆合并失败由 writer 内部降级处理，不阻断主流程；依赖 Spring 线程池调度，
            // 避免记忆三连 LLM 调用阻塞 SSE 收尾导致前端长时间等待「完成」状态。
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    markdownMemoryWriter.updateForSession(memoryRef, transcriptOf(memoryRef));
                    log.debug("分层记忆总结完成：session={}", memoryRef.sessionId());
                } catch (Exception e) {
                    log.warn("分层记忆总结异常（异步非阻塞，不影响主流程）：session={} err={}", memoryRef.sessionId(), e.getMessage());
                }
            });
            // 本轮事件流收尾：SSE 正常结束（不再靠心跳悬挂），并销毁 sink 供下一轮重建
            publisher.complete(ref.sessionId());
        }
    }

    /** 组装会话完整记录文本（供记忆总结使用，user/assistant 交替）。 */
    private String transcriptOf(SessionRef ref) {
        List<SessionSnapshot.MessageRecord> records = sessionRepository.loadMessages(ref.sessionId());
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SessionSnapshot.MessageRecord r : records) {
            sb.append(r.role()).append(": ").append(r.content()).append('\n');
        }
        return sb.toString();
    }

    /** 是否确认续跑请求（extra.confirm 为非空操作描述）。 */
    private boolean isConfirmRequest(UserInput input) {
        Object confirm = input == null || input.extra() == null ? null : input.extra().get("confirm");
        return confirm instanceof Map<?, ?> m && !m.isEmpty();
    }

    /**
     * 意图解析（轻量模型调用）：解析用户最新输入，产出简单意图总结，并判断该输入是否
     * 引用了历史上下文（选项/待办/之前的对话）以及是否涉及文件变动。
     *
     * <p>用于「与历史无关则以用户为主；引用历史且涉及文件变动则先询问确认」的控制逻辑。
     * 直接同步调用模型（不经引擎轮），不写会话状态、不推事件流、不发 stop，
     * 避免解析轮污染正文/提前收尾；JSON 解析失败时返回 {@code null}，调用方回退为直接执行。</p>
     */
    private IntentParse parseIntent(SessionRef ref, String content, String modelId) {
        // 附上最近一段会话历史，供模型判断该输入是否引用了之前的上下文（选项编号/待办/对话），
        // 纠正「无历史则无法判定引用」的盲区（P0-1 意图解析门实际生效的前提）
        String historyContext = recentHistory(ref);
        String goal = "请解析用户最新输入「" + content + "」，仅输出如下 JSON（不要输出其他任何内容）：\n"
                + "{\"independent\":true,\"summary\":\"一句话总结用户意图\",\"fileRelated\":false,\"confirmQuestion\":\"\"}\n"
                + (historyContext.isBlank() ? "" : "【最近对话历史】\n" + historyContext + "\n")
                + "判定规则：\n"
                + "1. independent：该输入是否是一个全新的独立请求。若明显是在回应之前的选项编号/待办/问题"
                + "（如单个数字、'继续'、'对，选1'、'写那个报告'等），为 false；否则为 true。\n"
                + "2. summary：用一句话概括用户真正想做的事。\n"
                + "3. fileRelated：该意图是否涉及文件变动（写入/修改/删除/重命名文件等）。\n"
                + "4. confirmQuestion：若该输入引用了历史且需要先向用户确认才能执行，给出一个简短自然的确认问题"
                + "（如'您是想选择第 1 项（……）吗？'）；若不需要确认则为空字符串。\n"
                + "注意：若 independent 为 true，说明与历史无关，以用户输入为准，confirmQuestion 必须为空字符串。";
        try {
            ChatModel model = modelRouter.resolve(modelId);
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(goal), UserMessage.from(content)))
                    .build();
            ChatResponse resp = model.chat(request);
            String text = resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (text == null || text.isBlank()) {
                log.warn("意图解析无返回，回退直接执行：session={}", ref.sessionId());
                return null;
            }
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = OBJECT_MAPPER.readTree(text.substring(start, end + 1));
            boolean independent = node.path("independent").asBoolean(true);
            boolean fileRelated = node.path("fileRelated").asBoolean(false);
            String summary = node.path("summary").asText("");
            String confirm = node.path("confirmQuestion").asText("");
            return new IntentParse(independent, summary, fileRelated, confirm);
        } catch (Exception e) {
            log.warn("意图解析失败，回退直接执行：session={} err={}", ref.sessionId(), e.getMessage());
            return null;
        }
    }

    /** 取会话状态最近一段历史（最多 6 条、每条截断）作为意图解析的上下文参考；无则返回空串。 */
    private String recentHistory(SessionRef ref) {
        ConversationStateManager.SessionState state = stateManager.find(ref.sessionId()).orElse(null);
        if (state == null || state.messages() == null || state.messages().isEmpty()) {
            return "";
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = state.messages();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, messages.size() - 6);
        for (int i = from; i < messages.size(); i++) {
            dev.langchain4j.data.message.ChatMessage m = messages.get(i);
            String role = m instanceof dev.langchain4j.data.message.UserMessage ? "user"
                    : (m instanceof dev.langchain4j.data.message.AiMessage ? "assistant"
                    : (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage ? "tool"
                    : "system"));
            if (m instanceof dev.langchain4j.data.message.SystemMessage) {
                continue;
            }
            String text;
            try {
                text = m.type() != null ? m.toString() : "";
            } catch (Exception e) {
                text = "";
            }
            sb.append(role).append(": ").append(truncate(text, 200)).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 意图解析结果。 */
    private record IntentParse(boolean independent, String summary, boolean fileRelated, String confirmQuestion) {
    }

    /** 取会话状态中最近一条用户消息作为续跑目标；无则返回空。 */
    private String lastUserGoal(ConversationStateManager.SessionState state) {
        if (state == null || state.messages() == null) {
            return "";
        }
        for (int i = state.messages().size() - 1; i >= 0; i--) {
            if (state.messages().get(i) instanceof UserMessage um) {
                String text = um.singleText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private void handleConfirm(SessionRef ref, UserInput input) {
        Object confirm = input.extra() == null ? null : input.extra().get("confirm");
        if (!(confirm instanceof Map<?, ?> m) || m.isEmpty()) {
            return;
        }
        String opTypeStr = m.get("opType") == null ? "WRITE" : String.valueOf(m.get("opType"));
        String pathStr = m.get("path") == null ? "" : String.valueOf(m.get("path"));
        FileOp.OpType opType;
        try {
            opType = FileOp.OpType.valueOf(opTypeStr);
        } catch (IllegalArgumentException e) {
            opType = FileOp.OpType.WRITE;
        }
        FileOp op = new FileOp(opType).workspaceId(ref.workspaceId()).path(pathStr);
        Object argsObj = m.get("args");
        if (argsObj instanceof Map<?, ?> args) {
            op.args(new HashMap<>((Map<String, Object>) args));
        }
        permissionService.allowOnce(op);
        log.info("用户确认高危操作：session={} op={} {}", ref.sessionId(), op.opType(), op.path());
    }

    private String titleFor(SessionRef ref, String content) {
        SessionRepository.SessionMeta existing = sessionRepository.loadMeta(ref.sessionId());
        if (existing != null && existing.title() != null && !existing.title().isBlank()
                && !"对话".equals(existing.title())) {
            return existing.title();
        }
        return content == null || content.isBlank() ? "对话" : content.substring(0, Math.min(content.length(), 24));
    }

    private ConversationCtx buildCtx(SessionRef ref, String content, String modelId) {
        Map<String, Object> extra = new HashMap<>();
        if (modelId != null && !modelId.isBlank()) {
            extra.put(MODEL_ID_KEY, modelId);
        }
        return ConversationCtx.builder()
                .sessionRef(ref)
                .phase(Phase.ACT)
                .permissionLevel(workspaceConfig.permissionLevelOf(ref.workspaceId())
                        .orElse(PermissionLevel.defaultValue()))
                .goal(content)
                .extra(extra)
                .build();
    }

    /**
     * 修复会话绑定的失效/为空工作空间：返回带有效 workspaceId 的会话引用；
     * 必要时把修复结果持久化到会话元数据。优先自建工作空间，其次内置默认。
     */
    private SessionRef repairWorkspace(SessionRef ref) {
        if (ref == null) {
            return null;
        }
        String wsId = ref.workspaceId();
        if (wsId != null && !wsId.isBlank() && workspaceConfig.getWorkspace(wsId).isPresent()) {
            return ref;
        }
        String repaired = workspaceConfig.listWorkspaces().stream()
                .filter(w -> !w.builtin())
                .findFirst()
                .or(() -> workspaceConfig.listWorkspaces().stream().findFirst())
                .map(Workspace::workspaceId)
                .orElse(null);
        if (repaired == null || repaired.equals(wsId)) {
            return ref;
        }
        log.warn("会话工作空间失效/为空，自动修复：session={} oldWs={} newWs={}",
                ref.sessionId(), wsId == null ? "(空)" : wsId, repaired);
        SessionRef fixed = new SessionRef(ref.sessionId(), ref.userId(), repaired);
        try {
            // 保留原标题，仅更新工作空间绑定
            sessionRepository.upsertMeta(fixed, titleFor(ref, ""));
        } catch (Exception e) {
            log.warn("持久化工作空间修复失败：{}", ref.sessionId(), e);
        }
        return fixed;
    }

    /** 取出用户在界面选定的模型端点（未指定返回 null，由模型路由回退主端点）。 */
    private String modelIdOf(UserInput input) {
        if (input == null || input.extra() == null) {
            return null;
        }
        Object raw = input.extra().get(MODEL_ID_KEY);
        return raw == null ? null : String.valueOf(raw);
    }

    private String permissionMode(SessionRef ref) {
        return switch (workspaceConfig.permissionLevelOf(ref.workspaceId())
                .orElse(PermissionLevel.defaultValue())) {
            case READ_ONLY -> "default";
            case MODIFY -> "acceptEdits";
            case FULL -> "bypassPermissions";
        };
    }
}
