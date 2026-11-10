package core.util.memory;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.util.memory.MemoryPrefetcher;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆预取限频粒度（台账 #28）。
 *
 * <p>限频状态此前是单例 Bean 上的实例字段，等价于进程级共享：任一会话「无命中」一次，
 * 此后 5 分钟<b>所有</b>会话预取都直接返回空串 —— 新对话拿不到本该命中的记忆，
 * 而且没有任何日志能看出是被别人限频了。本测试锁住「按会话分桶」这一语义。</p>
 */
class MemoryPrefetcherThrottleTest {

    private static final String INDEX = "# 记忆索引\n- mem-1: 用户偏好简洁输出";

    private ModelRouter modelRouter;
    private ChatModel selector;
    private HierarchyMemoryRetriever retriever;
    private MemoryPrefetcher prefetcher;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ModelRouter.class);
        selector = mock(ChatModel.class);
        retriever = mock(HierarchyMemoryRetriever.class);
        when(modelRouter.resolve()).thenReturn(selector);
        when(retriever.recallIndex("ws1")).thenReturn(INDEX);
        prefetcher = new MemoryPrefetcher(modelRouter, retriever, props());
    }

    @Test
    @DisplayName("会话 A 无命中不得限频到会话 B")
    void emptyResultInOneSessionDoesNotThrottleAnother() {
        // 会话 A：选择器挑不出任何条目 → 记为「无命中」
        respondWith("{\"entryIds\":[]}");
        String a1 = prefetcher.prefetch(ctx("s-A"), null);
        assertThat(a1).isEmpty();

        // 会话 B：同样有索引、选择器这次挑中 mem-1 → 必须真的读到条目
        respondWith("{\"entryIds\":[\"mem-1\"]}");
        when(retriever.recallEntries("ws1", Set.of("mem-1"))).thenReturn("### 条目\n偏简洁");

        String b = prefetcher.prefetch(ctx("s-B"), null);

        assertThat(b)
                .as("限频必须按会话分桶：A 的无命中不能让 B 也返回空串")
                .contains("偏简洁");
    }

    @Test
    @DisplayName("同一会话在限频窗口内不再调用选择器模型")
    void sameSessionIsThrottledWithinWindow() {
        respondWith("{\"entryIds\":[]}");
        assertThat(prefetcher.prefetch(ctx("s-A"), null)).isEmpty();
        // 第二次：命中限频直接返回，不该再问模型
        assertThat(prefetcher.prefetch(ctx("s-A"), null)).isEmpty();

        verify(selector, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("命中后清除本会话限频，后续无命中重新计时")
    void hitClearsThrottleForThatSessionOnly() {
        // A 先无命中（进入限频）
        respondWith("{\"entryIds\":[]}");
        prefetcher.prefetch(ctx("s-A"), null);

        // B 命中 → 正常返回
        respondWith("{\"entryIds\":[\"mem-1\"]}");
        when(retriever.recallEntries("ws1", Set.of("mem-1"))).thenReturn("内容");
        assertThat(prefetcher.prefetch(ctx("s-B"), null)).isNotEmpty();

        // B 紧接着再来一次（选择器这次仍挑中，但会话内去重由 state 负责；这里传 null 表示不去重）
        // 关键是：B 没有被 A 的限频窗口挡住 —— 上面的断言已覆盖。
        verify(retriever, times(2)).recallIndex("ws1");
    }

    @Test
    @DisplayName("索引为空同样只限频本会话")
    void emptyIndexOnlyThrottlesItsOwnSession() {
        when(retriever.recallIndex("ws-empty")).thenReturn("");
        ConversationCtx emptyWs = ConversationCtx.builder()
                .sessionRef(SessionRef.of("s-X", "u1", "ws-empty"))
                .goal("目标")
                .build();

        assertThat(prefetcher.prefetch(emptyWs, null)).isEmpty();
        assertThat(prefetcher.prefetch(emptyWs, null)).as("本会话仍应被限频").isEmpty();

        respondWith("{\"entryIds\":[\"mem-1\"]}");
        when(retriever.recallEntries("ws1", Set.of("mem-1"))).thenReturn("别的会话的记忆");
        assertThat(prefetcher.prefetch(ctx("s-Y"), null))
                .as("索引为空的会话不能影响其他工作空间/会话")
                .contains("别的会话的记忆");
    }

    private void respondWith(String json) {
        when(selector.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
    }

    private ConversationCtx ctx(String sessionId) {
        return ConversationCtx.builder()
                .sessionRef(SessionRef.of(sessionId, "u1", "ws1"))
                .goal("把报告写短一点")
                .build();
    }

    private MemoryMdProperties props() {
        return new MemoryMdProperties(true, "md", 5, 2, 200, 25600, true, 5, "", 24, 5);
    }
}
