package com.lucky.agent.core.util.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆预取选择器（对标 Claude Code Memory Prefetch）。
 *
 * <p>每轮引擎 run 组装 system prompt 时调用：把「两级记忆索引」交给轻量选择器模型，
 * 挑出与本轮目标最相关的 ≤{@code topK} 条条目 id，取全文注入本期上下文；
 * 同会话内已注入过的条目不重复注入（readFileState 等价物）。选择器失败/超时返回空，
 * 调用方回退「索引全量注入+记忆工具」，保证链路降级可用、不阻断主流程。</p>
 */
@Slf4j
public class MemoryPrefetcher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelRouter modelRouter;
    private final HierarchyMemoryRetriever retriever;
    private final MemoryMdProperties props;
    /** 上次「无命中」时间戳（毫秒）：连续无命中时短暂限频，避免每轮引擎白调选择器。 */
    private static final long EMPTY_THROTTLE_MS = 300_000L;
    /**
     * 限频状态<b>按会话</b>记录（台账 #28）。
     *
     * <p>此前是单个实例字段 {@code lastEmptyAt}，而本类是单例 Bean —— 等价于进程级共享：
     * 任一会话「无命中」一次，此后 5 分钟<b>所有</b>会话的预取都直接返回空串，
     * 新开的对话会拿不到本该命中的记忆。按 sessionId 分桶后，限频只影响触发它的那个会话。</p>
     */
    private final Map<String, Long> lastEmptyAt = new ConcurrentHashMap<>();

    /** 限频状态最多跟踪的会话数：超出后先清过期项，仍超则整体重置（记忆是增强能力，宁可多调一次也不无限增长）。 */
    private static final int MAX_TRACKED_SESSIONS = 512;

    public MemoryPrefetcher(ModelRouter modelRouter, HierarchyMemoryRetriever retriever,
                            MemoryMdProperties props) {
        this.modelRouter = modelRouter;
        this.retriever = retriever;
        this.props = props;
    }

    /**
     * 预取一步：读索引 → 选择器选 TopN → 过滤会话内已注入 → 取条目全文。
     *
     * @param ctx   会话上下文（含 workspaceId/sessionId/goal）
     * @param state 会话状态（维护会话内已注入条目集合，去重）
     * @return 命中条目全文（去重后）；无命中/选择器失败/索引为空返回空串
     */
    public String prefetch(ConversationCtx ctx, ConversationStateManager.SessionState state) {
        if (!props.prefetchEnabled() || ctx == null) {
            return "";
        }
        // 连续无命中 → 短暂限频（5 分钟内不再调选择器，避免取消/空索引场景下反复白调模型）。
        // 注意：限频窗口内返回空串，不再触发任何模型调用，从根上杜绝「用户没提问却被反复调用模型」。
        String throttleKey = throttleKey(ctx);
        if (throttled(throttleKey)) {
            return "";
        }
        String workspaceId = ctx.workspaceId();
        String indexText = retriever.recallIndex(workspaceId);
        if (indexText == null || indexText.isBlank()) {
            // 索引本身为空也计入「无命中」，避免每次引擎循环都重复读盘/重复判断
            markEmpty(throttleKey);
            return "";
        }
        String goal = ctx.goal() == null ? "" : ctx.goal();
        Set<String> selected = selectIds(goal, indexText);
        if (selected.isEmpty()) {
            markEmpty(throttleKey);
            return "";
        }
        // 会话内去重：本次新命中（首次注入）的条目才读取全文
        Set<String> fresh = state == null ? selected : state.markPrefetched(selected);
        if (fresh.isEmpty()) {
            markEmpty(throttleKey);
            return "";
        }
        lastEmptyAt.remove(throttleKey);
        return retriever.recallEntries(workspaceId, fresh);
    }

    /** 限频键：以会话为粒度（无 sessionId 时退化为工作空间，最后兜底全局）。 */
    private static String throttleKey(ConversationCtx ctx) {
        try {
            if (ctx.sessionRef() != null && ctx.sessionRef().sessionId() != null
                    && !ctx.sessionRef().sessionId().isBlank()) {
                return ctx.sessionRef().sessionId();
            }
        } catch (RuntimeException ignored) {
            // sessionRef 缺省实现可按需拒绝，退化到下一级键即可
        }
        String ws = ctx.workspaceId();
        return (ws == null || ws.isBlank()) ? "__global__" : "ws:" + ws;
    }

    private boolean throttled(String key) {
        Long at = lastEmptyAt.get(key);
        return at != null && System.currentTimeMillis() - at < EMPTY_THROTTLE_MS;
    }

    /** 记录一次「无命中」；表满时先清过期项，仍满则整体重置，保证不随会话数无限增长。 */
    private void markEmpty(String key) {
        long now = System.currentTimeMillis();
        if (lastEmptyAt.size() >= MAX_TRACKED_SESSIONS) {
            lastEmptyAt.entrySet().removeIf(e -> now - e.getValue() >= EMPTY_THROTTLE_MS);
            if (lastEmptyAt.size() >= MAX_TRACKED_SESSIONS) {
                log.debug("记忆预取限频表已满（{}），整体重置", lastEmptyAt.size());
                lastEmptyAt.clear();
            }
        }
        lastEmptyAt.put(key, now);
    }

    /** 选择器：从索引中挑出与本轮目标最相关的条目 id（JSON {@code {"entryIds":[...]}}）。 */
    private Set<String> selectIds(String goal, String indexText) {
        String prompt = "你是记忆选择器。根据【当前任务目标】从【记忆索引】中选出最相关的条目（最多 "
                + props.prefetchTopK() + " 条，没有相关条目则输出空数组）。\n"
                + "只输出如下 JSON，不要输出其他任何内容：\n"
                + "{\"entryIds\":[\"mem-xxx\", \"mem-yyy\"]}\n"
                + "【记忆索引】\n" + indexText;
        try {
            ChatModel model = resolveSelector();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(prompt), UserMessage.from(goal)))
                    .build();
            ChatResponse resp = model.chat(request);
            String text = resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (text == null || text.isBlank()) {
                log.warn("记忆选择器无返回，回退索引注入：goalLen={}", goal.length());
                return Set.of();
            }
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Set.of();
            }
            JsonNode node = OBJECT_MAPPER.readTree(text.substring(start, end + 1));
            JsonNode idsNode = node.path("entryIds");
            if (!idsNode.isArray()) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (JsonNode n : idsNode) {
                String id = n.asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            // 控制在上限内
            if (ids.size() > props.prefetchTopK()) {
                return new HashSet<>(new ArrayList<>(ids).subList(0, props.prefetchTopK()));
            }
            return ids;
        } catch (Exception e) {
            log.warn("记忆选择器调用失败，回退索引注入：err={}", e.getMessage());
            return Set.of();
        }
    }

    /** 选择器模型：优先独立廉价端点（prefetch.model-id），否则主端点。 */
    private ChatModel resolveSelector() {
        String modelId = props.prefetchModelId();
        return (modelId == null || modelId.isBlank()) ? modelRouter.resolve() : modelRouter.resolve(modelId);
    }
}