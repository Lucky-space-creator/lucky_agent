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
import com.lucky.agent.core.runtime.budget.RunOverrides;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.config.PresetResolver;
import com.lucky.agent.core.util.engine.OptionsBlockParser;
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
    private final PresetResolver presetResolver;
    private final ConcurrentMap<String, Boolean> sessionStarted = new ConcurrentHashMap<>();

    /** 意图确认问句挂起的目标（sessionId → 待确认意图摘要）；供下一轮自由文本确认消费，打破询问死循环。 */
    private final ConcurrentMap<String, String> pendingConfirmGoal = new ConcurrentHashMap<>();

    /**
     * 条件选择挂起态（sessionId → 待选选项集）。决策 ⑥：超时无人选择时，
     * 由定时任务自动选中「推荐项」并作为用户输入续跑，避免会话永久悬挂。
     */
    private final ConcurrentMap<String, PendingChoice> pendingChoices = new ConcurrentHashMap<>();

    /** 条件选择挂起记录。 */
    private record PendingChoice(String question, List<AgentEvent.OptionItem> options, long deadlineMs) {
    }

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
                               RollbackService rollbackService,
                               PresetResolver presetResolver) {
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
        this.presetResolver = presetResolver;
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
        return Mono.<Map<String, Object>>fromCallable(() -> {
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
        pendingChoices.remove(ref.sessionId());
        metricsCollector.stop(ref.sessionId());
        stateManager.destroy(ref.sessionId());
        return sessionRepository.delete(ref.sessionId());
    }

    /**
     * 用户对条件选择作出应答：清除挂起态（含取消定时任务），返回是否确有挂起被消费。
     *
     * @param sessionId 会话
     * @return true 表示应答了一个处于挂起状态的选项集
     */
    public boolean resolveChoice(String sessionId) {
        PendingChoice removed = pendingChoices.remove(sessionId);
        return removed != null;
    }

    /** 会话是否正等待用户作出条件选择。 */
    public boolean awaitingChoice(String sessionId) {
        PendingChoice p = pendingChoices.get(sessionId);
        if (p == null) {
            return false;
        }
        if (System.currentTimeMillis() > p.deadlineMs()) {
            pendingChoices.remove(sessionId);
            return false;
        }
        return true;
    }

    /**
     * 安排条件选择超时自动选优（决策 ⑥：挂起，超时不选则由系统选最优项继续）。
     *
     * <p>超时后若用户仍未选择，取出推荐项（无推荐则第一项）作为用户输入发起新一轮运行，
     * 使会话自动推进而不永久悬挂。用户若已选择，因挂起态被移除而静默失效（幂等）。</p>
     *
     * @param ref   会话
     * @param text  引擎最终文本（内含已剥离选项块的说明文字，超时续跑时作为上下文目标）
     */
    private void scheduleOptionsTimeout(SessionRef ref, String text) {
        Optional<OptionsBlockParser.Parsed> parsed = OptionsBlockParser.parse(text);
        if (parsed.isEmpty()) {
            // 引擎已发 options 事件但文本无法复解析（不该发生）：不安排超时，交由用户手动选择
            log.warn("条件选择挂起但文本无法复解析，跳过超时自动选优：session={}", ref.sessionId());
            return;
        }
        OptionsBlockParser.Parsed p = parsed.get();
        long timeoutSec = presetResolver == null ? 300L : presetResolver.optionsTimeoutSec();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        pendingChoices.put(ref.sessionId(), new PendingChoice(p.question(), p.options(), deadline));
        log.info("条件选择挂起：session={} 选项数={} 超时={}s", ref.sessionId(), p.options().size(), timeoutSec);
        Schedulers.boundedElastic().schedule(() -> autoPickOnTimeout(ref, timeoutSec),
                timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 超时自动选优：挂起态仍在（用户未应答）时，以推荐项作为「用户输入」续跑。 */
    private void autoPickOnTimeout(SessionRef ref, long timeoutSec) {
        PendingChoice p = pendingChoices.get(ref.sessionId());
        if (p == null) {
            return; // 用户已选择或会话已销毁
        }
        AgentEvent.OptionItem pick = p.options().stream()
                .filter(o -> Boolean.TRUE.equals(o.recommended()))
                .findFirst()
                .orElse(p.options().get(0));
        if (!pendingChoices.remove(ref.sessionId(), p)) {
            return; // 竞态：用户恰在此时选择，礼让用户输入
        }
        log.info("条件选择超时（{}s）自动选优：session={} 选项={} {}",
                timeoutSec, ref.sessionId(), pick.id(), pick.label());
        try {
            AgentEventPublisher publisher = stateManager.publisher();
            publisher.publish(ref.sessionId(), AgentEvent.progress(ref.sessionId(),
                    "选项超时未选择，已自动采用推荐方案：" + pick.label()));
            // 以选项 label 作为用户输入续跑（复用正常提交路径，保证消息落盘与事件流一致）
            UserInput synthetic = UserInput.of(pick.label());
            execute(ref, synthetic);
        } catch (Exception e) {
            log.warn("条件选择超时自动续跑失败：session={} err={}", ref.sessionId(), e.getMessage());
        }
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
            // 条件选择续跑：本轮输入即用户对上一轮选项的应答（点选项或自定义补充），
            // 消费掉挂起态使超时任务失效，避免超时任务随后再自动选一遍造成重复续跑。
            if (!isConfirm) {
                resolveChoice(ref.sessionId());
            }
            // [修复 Q2 询问死循环] 上一轮若已发出意图确认问句，本轮输入视为对其回应：
            // 自由文本「是的/确定/删吧」等也视作确认，直接以最近用户目标续跑，不再二次询问；
            // 明确否定则取消。从而打破「确认→再问→再确认」的死循环。
            String pendingGoal = pendingConfirmGoal.get(ref.sessionId());
            if (isConfirm) {
                pendingConfirmGoal.remove(ref.sessionId()); // UI 确认按钮路径：消费挂起目标
            } else if (pendingGoal != null) {
                pendingConfirmGoal.remove(ref.sessionId());
                if (isNegation(content)) {
                    publisher.publish(ref.sessionId(), AgentEvent.contentDelta(ref.sessionId(), "已取消，本轮不执行。"));
                    sessionRepository.appendMessage(ref.sessionId(), "assistant", "已取消，本轮不执行。",
                            Instant.now().toString(), null);
                    publisher.publish(ref.sessionId(), AgentEvent.stop(ref.sessionId(), "success", "已取消"));
                    return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.SUCCESS).summary("已取消");
                }
                boolean affirm = isAffirmation(content);
                boolean shortReply = content.trim().length() <= 24;
                if (affirm || shortReply) {
                    isConfirm = true; // 自由文本确认：复用确认续跑路径，直接执行最近用户目标
                }
                // 否则 isConfirm 保持 false → 下方走正常意图解析（挂起目标已清除，不会死循环）
            }
            handleConfirm(ref, input);

            if (!isConfirm) {
                state.appendMessage(UserMessage.from(content));
                // 先按「截断兜底」落一次标题（保证列表立即可读），再异步用 LLM 精简为更准确的标题。
                // 注意顺序：必须在 upsertMeta 之前判定是否需要命名，否则会被本轮写入的标题掩盖。
                boolean needLlmTitle = needsTitle(ref);
                sessionRepository.upsertMeta(ref, titleFor(ref, content));
                if (needLlmTitle) {
                    // 与记忆总结同频：异步、非阻塞、失败静默保留截断标题
                    Schedulers.boundedElastic().schedule(() -> generateSessionTitle(memoryRef, content));
                }
                // 复用前端传入的 userTs 作为落盘定位键，保证回退时前后端 ts 一致命中
                String userTs = tsFromExtra(input, "userTs", Instant.now().toString());
                sessionRepository.appendMessage(ref.sessionId(), "user", content, userTs);
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
                    // [修复 Q2] 记录挂起目标，供下一轮自由文本确认（「是的」等）消费，避免再次询问
                    pendingConfirmGoal.put(ref.sessionId(),
                            (intent.summary() == null || intent.summary().isBlank()) ? content : intent.summary());
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
            ConversationCtx ctx = buildCtx(ref, goal, input);
            publisher.publish(ref.sessionId(), AgentEvent.progress(ref.sessionId(),
                    isConfirm ? "已确认，继续执行…" : "收到你的请求：" + content));

            // 统一走编排器：是否拆子任务由 PLAN 阶段模型判断（P2-1，不在代码层做强分流）。
            // 编排模式下引擎不逐次发 stop（suppressStop），由会话层统一收尾发布。
            Set<String> beforeCheckpoints = checkpointIds(ref.workspaceId());
            EngineRunResult result = orchestrator.run(ref, ctx, publisher);
            if (result != null) {
                String status = result.status() == null ? "" : result.status();
                boolean options = "options".equals(status);
                String reason = result.error() != null
                        ? "error"
                        : ("ask".equals(status) ? "ask"
                        : ("options".equals(status) ? "options"
                        : ("cancelled".equals(status) ? "cancelled"
                        : "success")));
                // 条件选择：选项事件已由引擎发布（含剥离选项块后的正文），此处不再重发 stop，
                // 避免前端把挂起误判为「本轮完成」；改为登记挂起态并安排超时自动选优。
                if (!options) {
                    publisher.publish(ref.sessionId(), AgentEvent.stop(ref.sessionId(),
                            reason, result.finalText(), currentTitle(ref.sessionId())));
                }
            }

            if (result == null) {
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR).error("引擎无返回结果");
            }
            // 条件选择挂起：登记待选集合并安排超时自动选优；本轮视为已完成（等待用户输入）
            if ("options".equals(result.status())) {
                scheduleOptionsTimeout(ref, result.finalText());
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.SUCCESS)
                        .summary(result.finalText() == null ? "" : result.finalText())
                        .tokenUsed(result.tokenUsed())
                        .model(result.model());
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
                // 复用前端传入的 assistantTs 作为落盘定位键，保证回退时前后端 ts 一致命中
                String assistantTs = tsFromExtra(input, "assistantTs", Instant.now().toString());
                // 落盘带上思考链：推理模型要求「请求带 tools 时」历史所有轮 reasoning_content 原样回传，
                // 只存正文会让跨进程回放重建出无思考链的 assistant 消息，带工具重放即 400。
                sessionRepository.appendMessage(ref.sessionId(), "assistant", result.finalText(),
                        assistantTs, newCheckpoints, lastAssistantThinking(state));
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

    /**
     * 取前端随 extra 传入的消息定位时间戳（userTs / assistantTs）。
     * <p>前端在提交时用 {@code new Date().toISOString()} 生成消息 ts 并随 extra 传入，后端落盘时复用同一字符串，
     * 使「回退到节点（rollbackToNode）/ 消息级回溯（rollbackMessage）/ 截断（truncateAfter）」按 ts 定位时
     * 前后端一致命中；非 Web 调用方未提供时回退到 {@link Instant#now()}。</p>
     */
    private String tsFromExtra(UserInput input, String key, String fallback) {
        if (input != null && input.extra() != null) {
            Object v = input.extra().get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return fallback;
    }

    /** 是否确认续跑请求（extra.confirm 为非空操作描述）。 */
    private boolean isConfirmRequest(UserInput input) {
        Object confirm = input == null || input.extra() == null ? null : input.extra().get("confirm");
        return confirm instanceof Map<?, ?> m && !m.isEmpty();
    }

    /** 输入是否为对确认问句的明确否定（含否定词且不含肯定词、且较短）。 */
    private boolean isNegation(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty() || t.length() > 16) {
            return false; // 较长文本不视为单纯否定
        }
        if (isAffirmation(t)) {
            return false; // 肯定词优先级更高（如「确定，删掉，别问了」）
        }
        return t.matches("(不|否|别|不要|不用|算了|取消|停止|暂不|先不|no|cancel|stop).*");
    }

    /** 输入是否包含肯定/确认意图词。 */
    private boolean isAffirmation(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        return t.matches(".*(是|对|确定|确认|删|好|行|继续|执行|可以|没问题|ok|yes|do|按|就|直接|好的).*");
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

    /**
     * 取会话状态中<b>最近一条</b> assistant 消息的思考链（reasoning_content），用于落盘回放。
     *
     * <p>只认最后一条 assistant：它就是本轮最终回答对应的消息（引擎在
     * {@code lastAi.toBuilder().text(finalText).build()} 时已保留 thinking）。
     * 若它本身没有思考链则返回 null——<b>不向前回溯</b>，否则会把上一轮的思考链
     * 错误地挂到本轮正文上。普通模型该值恒为 null，落盘行为与改动前一致。</p>
     */
    private String lastAssistantThinking(ConversationStateManager.SessionState state) {
        if (state == null || state.messages() == null) {
            return null;
        }
        for (int i = state.messages().size() - 1; i >= 0; i--) {
            if (state.messages().get(i) instanceof AiMessage ai) {
                return ai.thinking();
            }
        }
        return null;
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

    /**
     * 该会话是否需要（用 LLM）生成标题：仅空白/新会话需命名，已有真实标题的会话不重复生成。
     * <p>必须在写入本轮标题<b>之前</b>调用，否则会被刚写入的截断标题掩盖。</p>
     */
    private boolean needsTitle(SessionRef ref) {
        SessionRepository.SessionMeta existing = sessionRepository.loadMeta(ref.sessionId());
        if (existing == null || existing.title() == null || existing.title().isBlank()) {
            return true;
        }
        String t = existing.title().trim();
        return "新会话".equals(t) || "对话".equals(t);
    }

    /**
     * 用 LLM 由首条用户消息生成精简会话标题（≤16 字），异步执行、失败静默。
     *
     * <p>复用会话标题的「前端兜底 + 后端精简」两层策略：此处在后台把截断标题替换为语义更准确的标题；
     * 若上一轮已生成过真实标题（用户可能已重命名），则不再覆盖。</p>
     */
    private void generateSessionTitle(SessionRef ref, String firstContent) {
        try {
            if (firstContent == null || firstContent.isBlank()) {
                return;
            }
            // 双重校验：异步执行期间标题可能已被用户/其他轮次改写，避免覆盖真实标题
            SessionRepository.SessionMeta current = sessionRepository.loadMeta(ref.sessionId());
            if (current != null && current.title() != null) {
                String t = current.title().trim();
                if (!t.isEmpty() && !"新会话".equals(t) && !"对话".equals(t) && t.length() != 24) {
                    return;
                }
            }
            String prompt = "请为下面这段用户请求生成一个简洁的中文会话标题，要求：\n"
                    + "1. 不超过 16 个汉字；\n"
                    + "2. 直接概括核心意图，不要引号、不要句号、不要「关于」「请问」等赘词；\n"
                    + "3. 只输出标题本身，不要任何解释或前后缀。\n\n"
                    + "用户请求：\n" + truncate(firstContent, 400);
            ChatModel model = modelRouter.resolveMemory();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(prompt), UserMessage.from(firstContent)))
                    .build();
            ChatResponse resp = model.chat(request);
            String text = resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (text == null || text.isBlank()) {
                return;
            }
            String title = cleanTitle(text);
            if (title.isEmpty()) {
                return;
            }
            sessionRepository.upsertMeta(ref, title);
            log.debug("会话标题已由 LLM 精简：session={} title={}", ref.sessionId(), title);
        } catch (Exception e) {
            log.warn("生成会话标题失败（保留兜底标题）：session={} err={}", ref.sessionId(), e.getMessage());
        }
    }

    /** 取会话当前落盘标题（供 stop 事件回传，前端据此同步列表标题）。 */
    private String currentTitle(String sessionId) {
        SessionRepository.SessionMeta meta = sessionRepository.loadMeta(sessionId);
        return meta == null ? null : meta.title();
    }

    /** 清洗 LLM 返回的标题：去引号/换行/前后缀，压缩空白并截断到 24 字上限。 */
    private String cleanTitle(String raw) {
        String t = raw.replaceAll("(?s)^```.*?```$", "").trim();
        t = t.replaceAll("^[\"'「『《\\s]+", "").replaceAll("[\"'」』》\\s]+$", "");
        t = t.replaceAll("\\s+", "");
        t = t.replaceAll("^标题[:：]\\s*", "");
        return t.length() <= 24 ? t : t.substring(0, 24);
    }

    /**
     * 组装会话上下文。
     *
     * <p>{@code extra} 只承载两类内容：① 本次运行的模型选择（{@code modelId}）；
     * ② {@link RunOverrides#PASSTHROUGH_KEYS} 白名单内的安全阀调节参数（回合上限 / token 预算），
     * 由通道按次透传。白名单刻意极窄：它是 run 级参数进入内核的唯一入口，
     * 权限级别、规则链、沙箱与并发等禁止覆盖项一律不在此列（见 CLI 方案 §2.5）。</p>
     */
    private ConversationCtx buildCtx(SessionRef ref, String content, UserInput input) {
        Map<String, Object> extra = new HashMap<>();
        String modelId = modelIdOf(input);
        if (modelId != null && !modelId.isBlank()) {
            extra.put(MODEL_ID_KEY, modelId);
        }
        if (input != null && input.extra() != null) {
            for (String key : RunOverrides.PASSTHROUGH_KEYS) {
                Object v = input.extra().get(key);
                if (v != null) {
                    extra.put(key, v);
                }
            }
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
