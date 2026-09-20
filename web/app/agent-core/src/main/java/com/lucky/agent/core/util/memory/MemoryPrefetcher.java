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
import java.util.Set;

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
    private volatile long lastEmptyAt = 0L;

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
        if (lastEmptyAt > 0 && System.currentTimeMillis() - lastEmptyAt < EMPTY_THROTTLE_MS) {
            return "";
        }
        String workspaceId = ctx.workspaceId();
        String indexText = retriever.recallIndex(workspaceId);
        if (indexText == null || indexText.isBlank()) {
            // 索引本身为空也计入「无命中」，避免每次引擎循环都重复读盘/重复判断
            lastEmptyAt = System.currentTimeMillis();
            return "";
        }
        String goal = ctx.goal() == null ? "" : ctx.goal();
        Set<String> selected = selectIds(goal, indexText);
        if (selected.isEmpty()) {
            lastEmptyAt = System.currentTimeMillis();
            return "";
        }
        // 会话内去重：本次新命中（首次注入）的条目才读取全文
        Set<String> fresh = state == null ? selected : state.markPrefetched(selected);
        if (fresh.isEmpty()) {
            lastEmptyAt = System.currentTimeMillis();
            return "";
        }
        lastEmptyAt = 0L;
        return retriever.recallEntries(workspaceId, fresh);
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