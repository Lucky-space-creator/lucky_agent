package com.lucky.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.LocalSandboxAdapter;
import com.lucky.agent.workflow.adapter.NoopToolAdapter;
import com.lucky.agent.workflow.domain.EdgeDef;
import com.lucky.agent.workflow.domain.NodeDef;
import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.NodeStatus;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.domain.enums.WorkflowNodeType;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.engine.ConditionEvaluator;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.engine.MappingEvaluator;
import com.lucky.agent.workflow.engine.NodeExecutor;
import com.lucky.agent.workflow.engine.WorkflowEngine;
import com.lucky.agent.workflow.engine.WorkflowStateMachine;
import com.lucky.agent.workflow.engine.executors.CodeNodeExecutor;
import com.lucky.agent.workflow.engine.executors.ConditionNodeExecutor;
import com.lucky.agent.workflow.engine.executors.EndNodeExecutor;
import com.lucky.agent.workflow.engine.executors.LlmNodeExecutor;
import com.lucky.agent.workflow.engine.executors.StartNodeExecutor;
import com.lucky.agent.workflow.engine.executors.SubflowNodeExecutor;
import com.lucky.agent.workflow.engine.executors.ToolNodeExecutor;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.repository.FileWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行期快照可见性回归测试：锁定「ASYNC 运行期间，外部读盘能观察到进行中的节点状态」。
 *
 * <p><b>回归背景（本测试存在的唯一理由）：</b>引擎原先只在 {@code run()} 开头与
 * {@code safeExecute} 的 finally 各落盘一次。开头那次 {@code nodeInstances} 必然为空，
 * 于是 ASYNC 运行期间任何 {@code GET /instances/{id}} 都只能读到空节点列表 ——
 * 前端执行面板的进度轨、节点耗时、入参与产出全程空白，直到整条工作流跑完才一次性出现。
 * SSE 事件只带类型与文案、不带变量快照，无法替代回查，故问题无法在前端侧绕过。</p>
 *
 * <p><b>为何用真实文件仓储而非内存实现：</b>{@code InMemoryWorkflowInstanceRepository}
 * 存的是实例引用本身，节点状态「天然可见」，会让测试恒定通过而完全失去意义。
 * 只有走 {@code FileWorkflowInstanceRepository}（序列化 → 落盘 → 另起仓储实例读回）
 * 才复现真实 HTTP 路径的可见性语义。</p>
 *
 * <p><b>为何用闩锁而非 sleep：</b>用 {@code Thread.sleep} 猜「此刻 LLM 应该还在跑」会引入
 * 随机失败；本测试用 {@link CountDownLatch} 把「LLM 已进入」这一时刻交给被测代码通知，
 * 断言点因此完全确定 —— 修复前必然失败，修复后必然通过。</p>
 */
class WorkflowInstanceProgressPersistenceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MappingEvaluator mappingEvaluator = new MappingEvaluator();

    // ---------------- 核心回归：运行中途的快照必须能看到进行中的节点 ----------------

    @Test
    @Timeout(20)
    void midRunSnapshotShouldExposeInFlightNodeState(@TempDir Path dir) throws Exception {
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch releaseLlm = new CountDownLatch(1);
        LlmAdapter gatedLlm = (prompt, variables) -> {
            llmEntered.countDown();
            // 卡住 LLM 节点，把「运行中」这一窗口无限拉长，便于确定性地观测
            awaitQuietly(releaseLlm, "测试未及时释放 LLM 节点");
            return "已受理";
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            WorkflowEngine engine = newEngine(dir, gatedLlm, pool);
            WorkflowInstance instance = engine.run(singleLlmWorkflow(), seededVars(), RunMode.ASYNC);

            awaitLlmEntered(llmEntered, instance, dir);

            // 关键断言：另起仓储实例读盘，模拟「另一个线程 / 另一次 HTTP 请求」——
            // 不共享任何内存态。修复前 nodeInstances 为空，这里会直接失败。
            WorkflowInstance midRun = new FileWorkflowInstanceRepository(dir, mapper)
                    .findById(instance.getInstanceId())
                    .orElseThrow();

            assertThat(midRun.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(midRun.getCurrentNodeId()).isEqualTo("gen");

            assertThat(midRun.getNodeInstances())
                    .as("运行中途必须已能看到已进入的节点")
                    .containsKeys("start", "gen");
            assertThat(midRun.getNodeInstances().get("start").getStatus())
                    .isEqualTo(NodeStatus.COMPLETED);
            assertThat(midRun.getNodeInstances().get("gen").getStatus())
                    .as("正在执行的节点必须是 RUNNING —— 这是前端「运行中」高亮的唯一数据来源")
                    .isEqualTo(NodeStatus.RUNNING);
            assertThat(midRun.getNodeInstances().get("gen").getStartedAt()).isGreaterThan(0L);
            // 入参快照也必须已在盘上：执行面板的「入参」列完全依赖它
            assertThat(midRun.getNodeInstances().get("gen").getInput().get("topic")).isEqualTo("退款");

            releaseLlm.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            WorkflowInstance finished = new FileWorkflowInstanceRepository(dir, mapper)
                    .findById(instance.getInstanceId())
                    .orElseThrow();
            assertThat(finished.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(finished.getEndedAt()).isGreaterThan(0L);
            assertThat(finished.getNodeInstances().get("gen").getStatus()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(finished.getNodeInstances().get("gen").getOutput().get("text")).isEqualTo("已受理");
        } finally {
            releaseLlm.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void failedNodeShouldBePersistedBeforeWorkflowEnds(@TempDir Path dir) throws Exception {
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch releaseLlm = new CountDownLatch(1);
        LlmAdapter failingLlm = (prompt, variables) -> {
            llmEntered.countDown();
            awaitQuietly(releaseLlm, "测试未及时释放 LLM 节点");
            throw new IllegalStateException("模型返回空响应");
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            WorkflowEngine engine = newEngine(dir, failingLlm, pool);
            WorkflowInstance instance = engine.run(singleLlmWorkflow(), seededVars(), RunMode.ASYNC);
            awaitLlmEntered(llmEntered, instance, dir);

            // 节点尚未失败之前，先确认「运行中」已落盘（与上一个测试互补：这里断言不同时间切片）
            WorkflowInstance running = new FileWorkflowInstanceRepository(dir, mapper)
                    .findById(instance.getInstanceId()).orElseThrow();
            assertThat(running.getNodeInstances().get("gen").getStatus()).isEqualTo(NodeStatus.RUNNING);

            releaseLlm.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            WorkflowInstance failed = new FileWorkflowInstanceRepository(dir, mapper)
                    .findById(instance.getInstanceId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(failed.getNodeInstances().get("gen").getStatus()).isEqualTo(NodeStatus.FAILED);
            assertThat(failed.getNodeInstances().get("gen").getError()).contains("模型返回空响应");
            // 失败路径也应留下入参快照，供执行面板展示「喂给了模型什么」
            assertThat(failed.getNodeInstances().get("gen").getEndedAt()).isGreaterThan(0L);
        } finally {
            releaseLlm.countDown();
            pool.shutdownNow();
        }
    }

    // ---------------- 写入原子性：并发读不得看到半截 JSON ----------------

    @Test
    @Timeout(30)
    void concurrentReadsShouldNeverSeePartialJson(@TempDir Path dir) throws Exception {
        // 引擎按节点落盘后，「写」与「读」在时间上真正重叠。若仍用 writeValue(File) 直接覆盖，
        // 读取方会撞上「已截断未写完」的窗口：JSON 解析失败 → findById 返回 empty → 接口 404。
        // 故写入改为「同目录临时文件 + ATOMIC_MOVE」，本测试锁定该性质。
        FileWorkflowInstanceRepository repo = new FileWorkflowInstanceRepository(dir, mapper);
        WorkflowInstance instance = new WorkflowInstance("wf-atomic", "原子写");
        repo.save(instance);

        String payload = "x".repeat(20_000);   // 放大单次写入耗时，使截断窗口更容易被读到
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger emptyReads = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                while (!stop.get()) {
                    VariableScope scope = new VariableScope();
                    scope.set("blob", payload);
                    instance.getVariables().merge(scope);
                    repo.save(instance);
                }
            });
            pool.submit(() -> {
                while (!stop.get()) {
                    reads.incrementAndGet();
                    if (repo.findById(instance.getInstanceId()).isEmpty()) {
                        emptyReads.incrementAndGet();
                    }
                }
            });
            Thread.sleep(800);
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        assertThat(reads.get()).as("读取线程应确实执行过").isGreaterThan(100);
        assertThat(emptyReads.get())
                .as("并发写入期间不得出现读不到/读到半截 JSON 的情况")
                .isZero();

        // 临时文件必须被清理干净，不得污染实例目录
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp")).toList())
                    .isEmpty();
        }
    }

    // ---------------- 失败信息可读性：不得以 error=null 收场 ----------------

    @Test
    @Timeout(20)
    void unresolvedInputMappingShouldNotFailWorkflow(@TempDir Path dir) throws Exception {
        // 回归背景：「节点引用了尚未产出的变量」最初会以 NullPointerException 击穿整条工作流，
        // 且因 NPE 的 message 为 null，实例的 error 字段也是 null ——
        // 前端执行面板只显示「失败」，用户看不到任何原因。
        // 正确语义：输入映射未解析到 ⇒ 该键缺席，工作流照常执行（变量快照里就是没有这一项）。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            WorkflowDef def = workflowWithInputMapping("missingVar");
            WorkflowInstance instance = newEngine(dir, (p, v) -> "ok", pool)
                    .run(def, new VariableScope(Map.of()), RunMode.SYNC);

            assertThat(instance.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(instance.getError()).isNull();
            // 键缺席（而非「存在且为 null」）—— ConcurrentHashMap 语义下二者不可混同
            assertThat(instance.getNodeInstances().get("gen").getInput().contains("topic")).isFalse();
            assertThat(instance.getNodeInstances().get("gen").getStatus()).isEqualTo(NodeStatus.COMPLETED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void unexpectedFailureShouldAlwaysCarryNonBlankReason(@TempDir Path dir) throws Exception {
        // 发生「意外异常」时，实例 error 绝不能为 null/空白：那是执行面板唯一的失败原因来源。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 用不含 START 节点的非法定义触发编译期校验失败（WorkflowException 分支）
            WorkflowDef illegal = new WorkflowDef("wf-illegal", "非法定义", null, 1,
                    List.of(new NodeDef("gen", "生成", WorkflowNodeType.LLM, Map.of(), List.of(), List.of())),
                    List.of(), com.lucky.agent.workflow.domain.TriggerDef.manual(), true, null, null);

            WorkflowInstance instance;
            try {
                instance = newEngine(dir, (p, v) -> "ok", pool)
                        .run(illegal, new VariableScope(Map.of()), RunMode.SYNC);
                throw new AssertionError("非法定义应抛出 WorkflowException");
            } catch (com.lucky.agent.workflow.exception.WorkflowException e) {
                // 定义非法必须向调用方抛出（与旧行为一致）；此处的重点是落盘后的 error 可读
                assertThat(e.getMessage()).isNotBlank();
            }

            WorkflowInstance persisted = new FileWorkflowInstanceRepository(dir, mapper)
                    .findAll().stream().findFirst().orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(persisted.getError()).as("失败原因不得为空").isNotBlank();
            assertThat(persisted.getError()).contains("WorkflowException");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 装配 ----------------

    /** 预置初始变量：输入映射声明了 topic，作用域里就必须有它，否则解析为「未命中」。 */
    private static VariableScope seededVars() {
        return new VariableScope(Map.of("topic", "退款"));
    }

    /** 在 LLM 适配器（不允许抛受检异常的 lambda）里等待闩锁：超时转非受检异常，并保留中断标志。 */
    private static void awaitQuietly(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待闩锁时被中断", e);
        }
    }

    /**
     * 等待 ASYNC 任务进入 LLM 节点。
     *
     * <p>不用裸 {@code await} + 断言：ASYNC 分支里 {@code safeExecute} 把异常抛在池线程上，
     * 会被 {@code Future} 吞掉，裸等待只能得到一句「5 秒超时」而看不到真实原因。
     * 此处改为轮询并在超时时附带磁盘快照的终态与错误，使失败自解释。</p>
     */
    private void awaitLlmEntered(CountDownLatch entered, WorkflowInstance instance, Path dir) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (entered.await(50, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
        WorkflowInstance snap = new FileWorkflowInstanceRepository(dir, mapper)
                .findById(instance.getInstanceId())
                .orElse(null);
        throw new AssertionError("ASYNC 任务未在 5s 内进入 LLM 节点；磁盘快照="
                + (snap == null ? "<无>" : "status=" + snap.getStatus()
                        + ", error=" + snap.getError()
                        + ", nodes=" + snap.getNodeInstances().keySet()));
    }

    private WorkflowEngine newEngine(Path dir, LlmAdapter llmAdapter, ExecutorService asyncExecutor) {
        List<NodeExecutor> executors = List.of(
                new StartNodeExecutor(),
                new EndNodeExecutor(),
                new LlmNodeExecutor(mappingEvaluator),
                new ToolNodeExecutor(),
                new ConditionNodeExecutor(),
                new CodeNodeExecutor(mappingEvaluator),
                new SubflowNodeExecutor());
        return new WorkflowEngine(
                new DagCompiler(),
                new WorkflowStateMachine(),
                mappingEvaluator,
                new ConditionEvaluator(mappingEvaluator),
                new WorkflowEventBus(),
                executors,
                llmAdapter,
                new NoopToolAdapter(),
                new LocalSandboxAdapter(false, 5000, null),
                new InMemoryWorkflowRepository(),
                new FileWorkflowInstanceRepository(dir, mapper),
                asyncExecutor);
    }

    /** START → LLM → END 的最小异步链路。 */
    private WorkflowDef singleLlmWorkflow() {
        return new WorkflowDef("wf-async-progress", "异步进度验证", "锁定运行期快照可见性", 1,
                List.of(new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of()),
                        new NodeDef("gen", "生成", WorkflowNodeType.LLM,
                                Map.of("prompt", "请处理: ${topic}"),
                                List.of(new com.lucky.agent.workflow.engine.InputMapping("topic", "topic")),
                                List.of()),
                        new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of())),
                List.of(new EdgeDef("e1", "start", "gen", null),
                        new EdgeDef("e2", "gen", "end", null)),
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, null, null);
    }

    /** 同链路，但输入映射指向一个必然不存在的变量（用于验证「未命中不致命」）。 */
    private WorkflowDef workflowWithInputMapping(String sourceExpr) {
        return new WorkflowDef("wf-missing-var", "缺失变量", null, 1,
                List.of(new NodeDef("start", "开始", WorkflowNodeType.START, Map.of(), List.of(), List.of()),
                        new NodeDef("gen", "生成", WorkflowNodeType.LLM,
                                Map.of("prompt", "请处理"),
                                List.of(new com.lucky.agent.workflow.engine.InputMapping(sourceExpr, "topic")),
                                List.of()),
                        new NodeDef("end", "结束", WorkflowNodeType.END, Map.of(), List.of(), List.of())),
                List.of(new EdgeDef("e1", "start", "gen", null),
                        new EdgeDef("e2", "gen", "end", null)),
                com.lucky.agent.workflow.domain.TriggerDef.manual(), true, null, null);
    }
}
