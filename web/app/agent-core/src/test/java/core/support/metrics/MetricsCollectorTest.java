package core.support.metrics;

import com.lucky.agent.cache.support.toolresult.FingerprintCache;
import com.lucky.agent.common.cache.CacheProvider;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetricsCollector 聚合行为验收（P3 透明面板后端数据源）。
 *
 * 使用真实 AgentEventPublisher + Sinks 事件流，验证 token / 模型调用 / 工具调用 /
 * Skill / MCP / 子代理进度能从事件流正确聚合为指标快照。
 */
class MetricsCollectorTest {

    private AgentEventPublisher publisher;
    private FingerprintCache fingerprintCache;
    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        publisher = new AgentEventPublisher();
        CacheProvider provider = mock(CacheProvider.class);
        when(provider.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(null);
        fingerprintCache = new FingerprintCache(provider);
        collector = new MetricsCollector(publisher, fingerprintCache);
    }

    private void emit(AgentEvent e) {
        publisher.publish(e.sessionId(), e);
    }

    @Test
    void aggregatesTokenModelToolSkillMcpAndSubAgents() throws InterruptedException {
        String sid = "sess-metrics-1";
        collector.start(sid);

        emit(AgentEvent.token(sid, 1200, 8192, "gpt-x", false));
        emit(AgentEvent.action(sid, "c1", "file_read", Map.of("path", "/a.txt"), null));
        emit(AgentEvent.toolResult(sid, "c1", "file_read", true, "ok", null, null));
        emit(AgentEvent.action(sid, "c2", "file_read", Map.of("path", "/b.txt"), null));
        emit(AgentEvent.toolResult(sid, "c2", "file_read", true, "ok", null, null));
        emit(AgentEvent.skillInvoke(sid, "skill-a", "match"));
        emit(AgentEvent.mcpInvoke(sid, "srv-1", "mcp_tool", "source"));
        emit(AgentEvent.taskProgress(sid, "sub-1",
                AgentEvent.TaskProgressStatus.RUNNING, 2, 5));

        // 模拟 ReactEngine 直接上报模型调用
        collector.reportModelCall(sid);
        collector.reportModelCall(sid);

        Thread.sleep(50);
        Map<String, Object> snap = collector.snapshot(sid).snapshot();

        assertEquals(1200L, snap.get("tokenUsed"));
        assertEquals(2L, snap.get("modelCalls"));
        assertEquals("gpt-x", snap.get("lastModel"));
        assertEquals(8192L, snap.get("contextWindow"));
        assertEquals(1L, snap.get("skillInvokes"));
        assertEquals(1L, snap.get("mcpInvokes"));

        @SuppressWarnings("unchecked")
        Map<String, Long> tools = (Map<String, Long>) snap.get("toolCounts");
        assertEquals(2L, tools.get("file_read"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> subs = (Map<String, Map<String, Object>>) snap.get("subAgents");
        assertEquals(2, subs.get("sub-1").get("done"));
        assertEquals(5, subs.get("sub-1").get("total"));

        collector.stop(sid);
    }

    /**
     * 每次提交（{@code start}）都开启新一轮：本轮计数清零，会话累计继续累加。
     *
     * <p>这是「每次用户消息的轮数从 0 算」的回归保护：第二条消息不能沿用第一条的轮数，
     * 否则界面上的「推理轮次」会显示整个会话的总数。</p>
     */
    @Test
    void startBeginsNewTurnWithoutLosingSessionTotals() throws InterruptedException {
        String sid = "sess-metrics-turn";
        // 真实链路里 sink 至少有两个订阅者（SSE/CLI 渲染 + 指标收集），
        // 若只剩指标一个订阅者，其 dispose→resubscribe 会触发 multicast sink 自动终止。
        // 这里保留一个常驻订阅者来还原真实订阅拓扑。
        reactor.core.Disposable keeper = publisher.stream(sid).subscribe(e -> { });
        collector.start(sid);
        collector.reportModelCall(sid);
        collector.reportModelCall(sid);
        emit(AgentEvent.token(sid, 100, 8192, "m", false));
        Thread.sleep(50);

        Map<String, Object> first = collector.snapshot(sid).snapshot();
        assertEquals(2L, first.get("modelCalls"));
        assertEquals(100L, first.get("tokenUsed"));
        assertEquals(1L, first.get("turnIndex"));

        // 第二条用户消息
        collector.start(sid);
        collector.reportModelCall(sid);
        emit(AgentEvent.token(sid, 40, 8192, "m", false));
        Thread.sleep(50);

        Map<String, Object> second = collector.snapshot(sid).snapshot();
        assertEquals(1L, second.get("modelCalls"), "本轮只调了 1 次模型");
        assertEquals(40L, second.get("tokenUsed"), "本轮 token 不含上一轮");
        assertEquals(2L, second.get("turnIndex"));
        assertEquals(140L, second.get("sessionTokenUsed"), "会话累计仍跨轮累加");
        assertEquals(3L, second.get("sessionModelCalls"));

        collector.stop(sid);
        keeper.dispose();
    }

    @Test
    void cacheHitRateReflectsFingerprintCounter() {
        String sid = "sess-metrics-2";
        // 手动推动全局缓存计数（真实计数由 FingerprintCache 在命中/未命中时自增）
        fingerprintCache.getIfPresent("t", Map.of(), "h", "s", 1); // miss
        fingerprintCache.getIfPresent("t", Map.of(), "h", "s", 1); // miss
        collector.start(sid);
        emit(AgentEvent.token(sid, 10, 100, "m", false));

        Map<String, Object> snap = collector.snapshot(sid).snapshot();
        assertEquals(0L, snap.get("cacheHits"));
        assertEquals(2L, snap.get("cacheMisses"));
        assertTrue((double) snap.get("cacheHitRate") == 0.0);

        collector.stop(sid);
    }
}
