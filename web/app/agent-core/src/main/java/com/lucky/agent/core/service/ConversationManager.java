package com.lucky.agent.core.service;

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
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.metrics.MetricsCollector;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import com.lucky.agent.core.subagent.TaskDecomposer;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.permission.api.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import dev.langchain4j.data.message.UserMessage;
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

    private final ConversationStateManager stateManager;
    private final MemoryStore memoryStore;
    private final WorkspaceConfig workspaceConfig;
    private final AgentOrchestrator orchestrator;
    private final LifecycleHookDispatcher hookDispatcher;
    private final SessionRepository sessionRepository;
    private final PermissionService permissionService;
    private final MetricsCollector metricsCollector;
    private final ConcurrentMap<String, Boolean> sessionStarted = new ConcurrentHashMap<>();

    public ConversationManager(ConversationStateManager stateManager,
                               @Qualifier("userMemoryStore") MemoryStore memoryStore,
                               WorkspaceConfig workspaceConfig,
                               AgentOrchestrator orchestrator,
                               LifecycleHookDispatcher hookDispatcher,
                               SessionRepository sessionRepository,
                               PermissionService permissionService,
                               MetricsCollector metricsCollector) {
        this.stateManager = stateManager;
        this.memoryStore = memoryStore;
        this.workspaceConfig = workspaceConfig;
        this.orchestrator = orchestrator;
        this.hookDispatcher = hookDispatcher;
        this.sessionRepository = sessionRepository;
        this.permissionService = permissionService;
        this.metricsCollector = metricsCollector;
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
                .map(m -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("sessionId", m.sessionId());
                    entry.put("userId", m.userId());
                    entry.put("workspaceId", m.workspaceId() == null ? "" : m.workspaceId());
                    entry.put("title", m.title() == null ? "对话" : m.title());
                    entry.put("createdAt", m.createdAt());
                    entry.put("updatedAt", m.updatedAt());
                    return entry;
                })
                .toList());
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
        ConversationStateManager.SessionState state = stateManager.session(ref);
        if (state.running()) {
            return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR)
                    .error("会话正在运行中，请等待完成");
        }
        state.running(true);
        AgentEventPublisher publisher = stateManager.publisher();
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
                memoryStore.appendUser(ref.userId(), content, 0.8, "user");
            }

            // 确认续跑时以最近用户目标作为执行目标，避免以空文本重跑
            String goal = isConfirm ? lastUserGoal(state) : content;
            ConversationCtx ctx = buildCtx(ref, goal, modelIdOf(input));
            publisher.publish(ref.sessionId(), AgentEvent.thought(ref.sessionId(),
                    isConfirm ? "已确认，继续执行…" : "收到你的请求：" + content));

            // 统一走编排器：是否拆子任务由 PLAN 阶段模型判断（P2-1，不在代码层做强分流）。
            // 编排模式下引擎不逐次发 stop（suppressStop），由会话层统一收尾发布。
            EngineRunResult result = orchestrator.run(ref, ctx, publisher);
            if (result != null) {
                String reason = result.error() != null ? "error"
                        : (result.status() != null && result.status().equals("ask") ? "ask" : "success");
                publisher.publish(ref.sessionId(), AgentEvent.stop(ref.sessionId(),
                        reason, result.finalText()));
            }

            if (result == null) {
                return RunResult.of(ref.sessionId()).status(RunResult.RunStatus.ERROR).error("引擎无返回结果");
            }
            if (result.finalText() != null && !result.finalText().isBlank()) {
                sessionRepository.appendMessage(ref.sessionId(), "assistant", result.finalText(), Instant.now().toString());
                memoryStore.appendUser(ref.userId(), result.finalText(), 0.7, "observation");
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
            // 本轮事件流收尾：SSE 正常结束（不再靠心跳悬挂），并销毁 sink 供下一轮重建
            publisher.complete(ref.sessionId());
        }
    }

    /** 是否确认续跑请求（extra.confirm 为非空操作描述）。 */
    private boolean isConfirmRequest(UserInput input) {
        Object confirm = input == null || input.extra() == null ? null : input.extra().get("confirm");
        return confirm instanceof Map<?, ?> m && !m.isEmpty();
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
