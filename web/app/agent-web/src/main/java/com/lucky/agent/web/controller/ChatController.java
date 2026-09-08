package com.lucky.agent.web.controller;

import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.web.stream.ReactiveSsePublisher;
import com.lucky.agent.web.stream.WebChannel;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话接口（SSE 流式）。
 *
 * <p>提交立即返回（异步运行），运行过程经 {@code Flux<AgentEvent>} 经 SSE 实时推送；
 * 长任务在异步线程执行，不占用 Web 容器业务线程，POST 不阻塞等待结果。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 用户选定的模型端点在 extra 中的键（与 ConversationManager 约定一致）。 */
    private static final String MODEL_ID_KEY = "modelId";

    private final ReactiveSsePublisher ssePublisher;
    private final WebChannel webChannel;
    private final ConversationManager conversationManager;

    public ChatController(ReactiveSsePublisher ssePublisher, WebChannel webChannel,
                          ConversationManager conversationManager) {
        this.ssePublisher = ssePublisher;
        this.webChannel = webChannel;
        this.conversationManager = conversationManager;
    }

    /** 提交用户输入（无 sessionId 时新建会话）。立即返回，运行异步进行。 */
    @PostMapping("/messages")
    public Mono<Map<String, Object>> submit(@RequestBody ChatSubmitRequest request) {
        String sessionId = request.sessionId() == null ? UUID.randomUUID().toString() : request.sessionId();
        SessionRef ref = new SessionRef(sessionId, request.userId(), request.workspaceId());
        // 界面上选定的模型端点并入 extra 传给内核（不单独扩 SessionRef，保持会话定位语义不变）
        Map<String, Object> extra = request.extra() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.extra());
        if (request.modelId() != null && !request.modelId().isBlank()) {
            extra.put(MODEL_ID_KEY, request.modelId());
        }
        // fire-and-forget：运行经 publisher + SSE 推送，POST 立即返回
        webChannel.submit(ref, UserInput.of(request.content(), extra))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe();
        return Mono.just(Map.of("sessionId", sessionId, "accepted", true));
    }

    /** 订阅会话事件流（SSE）。 */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String sessionId,
                                                @RequestParam String userId,
                                                @RequestParam String workspaceId) {
        return ssePublisher.stream(new SessionRef(sessionId, userId, workspaceId));
    }

    /** 取消会话运行。 */
    @PostMapping("/{sessionId}/cancel")
    public Mono<Void> cancel(@PathVariable String sessionId,
                             @RequestParam String userId,
                             @RequestParam String workspaceId) {
        return webChannel.cancel(new SessionRef(sessionId, userId, workspaceId));
    }

    /** 会话快照（当前内存状态）。 */
    @GetMapping("/{sessionId}/snapshot")
    public Mono<SessionSnapshot> snapshot(@PathVariable String sessionId,
                                          @RequestParam String userId,
                                          @RequestParam String workspaceId) {
        return webChannel.snapshot(new SessionRef(sessionId, userId, workspaceId));
    }

    /** 会话历史（持久化恢复）。 */
    @GetMapping("/{sessionId}/history")
    public Mono<SessionSnapshot> history(@PathVariable String sessionId,
                                         @RequestParam String userId,
                                         @RequestParam String workspaceId) {
        return conversationManager.history(new SessionRef(sessionId, userId, workspaceId));
    }

    /** 会话列表。 */
    @GetMapping("/sessions")
    public Mono<List<Map<String, Object>>> sessions(@RequestParam String userId) {
        return conversationManager.listSessions(userId);
    }

    /** 新建空白会话（元数据落盘，前端「新对话」立即持久化）。 */
    @PostMapping("/sessions")
    public Mono<Map<String, Object>> createSession(@RequestBody SessionCreateRequest request) {
        return conversationManager.createSession(request.userId(), request.workspaceId(), request.title());
    }

    /** 更新会话元数据（重命名 / 切换工作空间）。 */
    @PutMapping("/{sessionId}")
    public Mono<Map<String, Object>> updateSession(@PathVariable String sessionId,
                                                   @RequestBody SessionUpdateRequest request) {
        SessionRef ref = new SessionRef(sessionId, request.userId(), request.workspaceId());
        return conversationManager.updateMeta(ref, request.title(), request.workspaceId());
    }

    /** 消息级回溯：撤销某条助手消息执行期间修改过的文件。 */
    @PostMapping("/{sessionId}/rollback")
    public Mono<Map<String, Object>> rollbackMessage(@PathVariable String sessionId,
                                                     @RequestBody RollbackMessageRequest request) {
        return conversationManager.rollbackMessage(sessionId, request.workspaceId(), request.messageTs())
                .map(n -> Map.of("restored", n));
    }

    /** 删除会话（内存 + 持久化）。 */
    @DeleteMapping("/{sessionId}")
    public Mono<Map<String, Boolean>> destroy(@PathVariable String sessionId,
                                              @RequestParam String userId) {
        return Mono.just(Map.of("removed", conversationManager.destroy(new SessionRef(sessionId, userId, null))));
    }

    /** 对话提交请求体。modelId 为界面选定的模型端点（可空，空则走默认主端点）。 */
    public record ChatSubmitRequest(String sessionId, String userId, String workspaceId,
                                    String content, Map<String, Object> extra, String modelId) {
    }

    /** 新建会话请求体。 */
    public record SessionCreateRequest(String userId, String workspaceId, String title) {
    }

    /** 更新会话元数据请求体（title / workspaceId 均为可空，缺省保持原值）。 */
    public record SessionUpdateRequest(String userId, String workspaceId, String title) {
    }

    /** 消息级回溯请求体。 */
    public record RollbackMessageRequest(String workspaceId, String messageTs) {
    }
}
