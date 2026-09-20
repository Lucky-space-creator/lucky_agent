package com.lucky.agent.core.util.subagent;

import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.repository.SubAgentResult;
import com.lucky.agent.core.repository.TaskSchedulerStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * 子代理调度器：串行（scheduleSerial）与并行 fan-out/fan-in（scheduleParallel）。
 * <p>并行度受 {@code core.subagentMaxConcurrency} 约束；子任务失败/超时在 SubAgentExecutor 内
 * 降级为失败结果，不静默丢弃。内部累计运行统计供状态面板查询（见 {@link #status()}）。</p>
 */
@Slf4j
public class TaskScheduler {

    private final SubAgentExecutor executor;
    private final ResultAggregator aggregator;
    private final CoreProperties properties;

    private final AtomicInteger activeRuns = new AtomicInteger();
    private final AtomicInteger runningSubAgents = new AtomicInteger();
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    public TaskScheduler(SubAgentExecutor executor, ResultAggregator aggregator, CoreProperties properties) {
        this.executor = executor;
        this.aggregator = aggregator;
        this.properties = properties;
    }

    /**
     * 串行执行一组子代理任务（依次执行，产出按序）。
     *
     * @param specs         子代理定义列表（与 tasks 一一对应）
     * @param tasks         任务列表
     * @param workspaceId   工作空间 ID
     * @param parentSession 主会话 ID
     * @return 子代理结果流
     */
    public Flux<SubAgentResult> scheduleSerial(List<SubAgentSpec> specs, List<String> tasks,
                                               String workspaceId, String parentSession) {
        if (invalid(specs, tasks)) {
            return Flux.empty();
        }
        List<Mono<SubAgentResult>> steps = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            steps.add(executeTracked(specs.get(i), tasks.get(i), workspaceId, parentSession));
        }
        return Flux.concat(steps).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 并行执行一组子代理任务（fan-out），并按子代理 id 聚合摘要（fan-in）。
     * <p>并行度受 {@code core.subagentMaxConcurrency} 限制；失败子代理被聚合器跳过。
     * 在途批次计数随订阅增减，值产出前确保已回减，保证调用方 {@code block()} 后状态一致。</p>
     *
     * @return 聚合后的总摘要文本
     */
    public Mono<String> scheduleParallel(List<SubAgentSpec> specs, List<String> tasks,
                                         String workspaceId, String parentSession) {
        if (invalid(specs, tasks)) {
            return Mono.just("");
        }
        int concurrency = properties.subagentMaxConcurrency();
        return Flux.range(0, specs.size())
                .flatMap(i -> executeTracked(specs.get(i), tasks.get(i), workspaceId, parentSession), concurrency)
                .collectList()
                .map(aggregator::aggregate)
                .doOnSubscribe(s -> activeRuns.incrementAndGet())
                .doOnNext(ignored -> activeRuns.decrementAndGet())
                .doOnError(e -> activeRuns.decrementAndGet())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 调度器运行状态快照。 */
    public TaskSchedulerStatus status() {
        return new TaskSchedulerStatus(activeRuns.get(), runningSubAgents.get(),
                properties.subagentMaxConcurrency(), totalCompleted.get(), totalFailed.get());
    }

    private Mono<SubAgentResult> executeTracked(SubAgentSpec spec, String task,
                                                String workspaceId, String parentSession) {
        runningSubAgents.incrementAndGet();
        return executor.execute(spec, task, workspaceId, parentSession)
                .doOnNext(result -> {
                    if (result.success()) {
                        totalCompleted.incrementAndGet();
                    } else {
                        totalFailed.incrementAndGet();
                    }
                })
                .doFinally(signal -> runningSubAgents.decrementAndGet());
    }

    private boolean invalid(List<SubAgentSpec> specs, List<String> tasks) {
        return specs == null || tasks == null || specs.size() != tasks.size() || specs.isEmpty();
    }
}
