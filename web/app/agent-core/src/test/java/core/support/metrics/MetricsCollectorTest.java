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
