package core.support.memory;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.memory.MemoryPrefetcher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆预取选择器测试：选择器输出 → 命中条目注入；同会话重复条目去重。
 */
class MemoryPrefetcherTest {

    private MemoryMdProperties props() {
        return new MemoryMdProperties(true, "md", 5, 2, 200, 25600, true, 5, "", 24, 5);
    }

    private ChatModel selectorReturning(String json) {
        ChatModel model = mock(ChatModel.class);
        dev.langchain4j.data.message.AiMessage ai = dev.langchain4j.data.message.AiMessage.from(json);
        // 用真实 ChatResponse 构造，避免 mock 链式调用导致的 UnfinishedStubbing
        ChatResponse resp = ChatResponse.builder().aiMessage(ai).build();
        // stub default 入口 chat(ChatRequest)，直接返回构造结果（不执行 default 体）
        org.mockito.Mockito.doReturn(resp).when(model).chat(any(ChatRequest.class));
        return model;
    }

    private ConversationStateManager.SessionState state() {
        return new ConversationStateManager.SessionState(
                new SessionRef("s-1", "u-1", "ws-1"), new CoreProperties(
                12, 30, 30, -1, false, 4, 300, 3, 2, "reactor", true, 120, 500, 0.3));
    }

    @Test
    void testPrefetch_ReturnsHitEntriesAndMarksDedup() {
        HierarchyMemoryRetriever retriever = mock(HierarchyMemoryRetriever.class);
        when(retriever.recallIndex("ws-1")).thenReturn("- [project] 些许\n- [user] 偏好");
        when(retriever.recallEntries(any(), any())).thenReturn("- [project] 后端用 JDK 17");

        ModelRouter router = mock(ModelRouter.class);
        ChatModel selector = selectorReturning("{\"entryIds\":[\"mem-abc\",\"mem-def\"]}");
        when(router.resolve()).thenReturn(selector);

        MemoryPrefetcher prefetcher = new MemoryPrefetcher(router, retriever, props());
        ConversationStateManager.SessionState state = state();
        ConversationCtx ctx = ConversationCtx.builder()
                .sessionRef(new SessionRef("s-1", "u-1", "ws-1"))
                .goal("看看后端环境").build();

        String first = prefetcher.prefetch(ctx, state);
        assertEquals("- [project] 后端用 JDK 17", first, "首次命中条目应注入");
        assertTrue(state.markPrefetched(List.of()).isEmpty());

        // 同会话再次预取同一批 id：全部已注入 → 不重复返回
        when(retriever.recallEntries(any(), any())).thenReturn("- [project] 后端用 JDK 17");
        String second = prefetcher.prefetch(ctx, state);
        assertEquals("", second, "同会话内已注入的条目不应重复注入");
    }

    @Test
    void testPrefetch_SelectorFailureReturnsEmpty() {
        HierarchyMemoryRetriever retriever = mock(HierarchyMemoryRetriever.class);
        when(retriever.recallIndex("ws-1")).thenReturn("- [project] 些许内容");
        ModelRouter router = mock(ModelRouter.class);
        when(router.resolve()).thenThrow(new RuntimeException("端点不可达"));

        MemoryPrefetcher prefetcher = new MemoryPrefetcher(router, retriever, props());
        String result = prefetcher.prefetch(ctx("ws-1"), state());

        assertEquals("", result, "选择器失败应返回空，由调用方回退索引注入");
    }

    @Test
    void testPrefetch_DisabledReturnsEmpty() {
        MemoryMdProperties disabled = new MemoryMdProperties(true, "md", 5, 2, 200, 25600, false, 5, "", 24, 5);
        MemoryPrefetcher prefetcher = new MemoryPrefetcher(mock(ModelRouter.class),
                mock(HierarchyMemoryRetriever.class), disabled);
        assertEquals("", prefetcher.prefetch(ctx("ws-1"), state()), "预取关闭应返回空");
    }

    private ConversationCtx ctx(String workspaceId) {
        return ConversationCtx.builder()
                .sessionRef(new SessionRef("s-1", "u-1", workspaceId))
                .goal("任务").build();
    }
}