package core.support.subagent;

import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.util.subagent.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TaskScheduler 并行调度单元测试（fan-out / fan-in 聚合 / 状态统计 / 非法入参）。
 */
class TaskSchedulerTest {

    private SubAgentSpec spec(String id) {
        return SubAgentSpec.builder().id(id).name(id).build();
    }

    private TaskScheduler scheduler(SubAgentExecutor executor) {
        // planMaxSteps, actMaxSteps, runMaxTurns, runMaxBudget, subagentEnabled,
        // subagentMaxConcurrency, subagentTaskTimeoutSec, orchestratorMaxIterations,
        // orchestratorMaxRetries, verificationEnabled, verificationTimeoutSec, retryBackoffMs
        return new TaskScheduler(executor, new ResultAggregator(),
                new CoreProperties(12, 30, 30, -1, true, 2, 300, 3, 2, "reactor", true, 120, 500, 0.3));
    }

    private SubAgentExecutor mockExecutorReturningSummaries() {
        SubAgentExecutor executor = mock(SubAgentExecutor.class);
        when(executor.execute(any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    SubAgentSpec spec = inv.getArgument(0);
                    return Mono.just(new SubAgentResult(spec.id(), "summary-" + spec.id(), true));
                });
        return executor;
    }

    @Test
    void testScheduleParallel_AggregatesAllResults() {
        TaskScheduler scheduler = scheduler(mockExecutorReturningSummaries());

        String aggregated = scheduler.scheduleParallel(
                List.of(spec("a"), spec("b")), List.of("t1", "t2"), "ws", "parent").block();

        assertTrue(aggregated.contains("summary-a"));
        assertTrue(aggregated.contains("summary-b"));
    }

    @Test
    void testScheduleParallel_FailedSubAgentsSkipped() {
        SubAgentExecutor executor = mock(SubAgentExecutor.class);
        when(executor.execute(any(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new SubAgentResult("a", "bad", false)));
        TaskScheduler scheduler = scheduler(executor);

        String aggregated = scheduler.scheduleParallel(
                List.of(spec("a")), List.of("t"), "ws", "p").block();

        assertEquals("", aggregated, "失败子代理摘要应被聚合器跳过");
        assertEquals(1, scheduler.status().totalFailed());
    }

    @Test
    void testScheduleParallel_InvalidInputReturnsEmpty() {
        TaskScheduler scheduler = scheduler(mock(SubAgentExecutor.class));
        assertEquals("", scheduler.scheduleParallel(List.of(), List.of(), "ws", "p").block());
        assertEquals("", scheduler.scheduleParallel(null, null, "ws", "p").block());
    }

    @Test
    void testStatus_TracksCompletedAndConcurrency() {
        TaskScheduler scheduler = scheduler(mockExecutorReturningSummaries());

        scheduler.scheduleParallel(List.of(spec("a")), List.of("t"), "ws", "p").block();

        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        TaskSchedulerStatus status = scheduler.status();
        assertEquals(2, status.maxConcurrency());
        assertEquals(1, status.totalCompleted());
        assertEquals(0, status.totalFailed());
        assertEquals(0, status.activeRuns(), "调度结束后在途批次应为 0");
        assertEquals(0, status.runningSubAgents());
    }
}
