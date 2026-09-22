package com.lucky.agent.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.LocalSandboxAdapter;
import com.lucky.agent.workflow.adapter.NoopLlmAdapter;
import com.lucky.agent.workflow.adapter.NoopToolAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import com.lucky.agent.workflow.api.WorkflowController;
import com.lucky.agent.workflow.engine.ConditionEvaluator;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.engine.LangGraphWorkflowEngine;
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
import com.lucky.agent.workflow.engine.trigger.IntervalTrigger;
import com.lucky.agent.workflow.engine.trigger.ManualTrigger;
import com.lucky.agent.workflow.engine.trigger.Trigger;
import com.lucky.agent.workflow.engine.trigger.WorkflowScheduler;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.repository.FileWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.FileWorkflowRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.InMemoryWorkflowRepository;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;
import com.lucky.agent.workflow.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流模块配置：装配引擎、执行器、适配器、仓储、调度器与 REST 通道。
 *
 * <p>与同级能力模块（agent-skill / agent-mcp / agent-memory …）保持一致：使用普通
 * {@link Configuration} + {@code @EnableConfigurationProperties}，由宿主的
 * {@code scanBasePackages = "com.lucky.agent"} 扫描装配。</p>
 *
 * <p><b>为何不用 {@code @AutoConfiguration} + {@code META-INF/spring/...AutoConfiguration.imports}：</b>
 * 该机制虽能装配普通 Bean，但本模块的 {@link com.lucky.agent.workflow.api.WorkflowController}
 * 必须走「{@code @RestController} + 组件扫描」路径才能被 WebFlux 的
 * {@code RequestMappingHandlerMapping} 识别为处理器（它只认注解，不认 @Bean 注册的普通对象），
 * 否则路由不注册 → 404 {@code No static resource api/workflows}。
 * 两条路径并存还会带来重复定义风险，故统一收敛到组件扫描。</p>
 *
 * <p>所有 Bean 仍标注 {@link ConditionalOnMissingBean}，宿主可用自定义实现覆盖。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowAutoConfiguration {

    // ---------------- 基础组件 ----------------

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEventBus workflowEventBus() {
        return new WorkflowEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public MappingEvaluator workflowMappingEvaluator() {
        return new MappingEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConditionEvaluator workflowConditionEvaluator(MappingEvaluator mappingEvaluator) {
        return new ConditionEvaluator(mappingEvaluator);
    }

    @Bean
    @ConditionalOnMissingBean
    public DagCompiler workflowDagCompiler() {
        return new DagCompiler();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowStateMachine workflowStateMachine() {
        return new WorkflowStateMachine();
    }

    // ---------------- 适配器（集成缝隙，可被宿主覆盖） ----------------

    @Bean
    @ConditionalOnMissingBean(LlmAdapter.class)
    public LlmAdapter workflowLlmAdapter() {
        return new NoopLlmAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(ToolAdapter.class)
    public ToolAdapter workflowToolAdapter() {
        return new NoopToolAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SandboxAdapter.class)
    public SandboxAdapter workflowSandboxAdapter(WorkflowProperties properties) {
        WorkflowProperties.Sandbox sandbox = properties.getSandbox();
        return new LocalSandboxAdapter(sandbox.isEnabled(), sandbox.getTimeoutMs(), sandbox.getWorkingDir());
    }

    // ---------------- 仓储 ----------------

    /**
     * 工作流定义仓储：优先落盘，无可用目录时才退化为内存实现。
     *
     * <p><b>解析顺序：</b>显式配置 {@code lucky.workflow.storage-dir} → 框架必备目录
     * {@code <root>/workflow}（即 {@code ~/.lucky_agent/workflow}，由 {@link WorkspaceDirs} 提供）→
     * 内存实现。注入 {@link ObjectProvider} 而非强依赖，使本模块脱离宿主（单测 / 独立运行）时
     * 仍可自洽装配。</p>
     *
     * <p><b>为何默认落盘：</b>内存实现下工作流定义随进程消失，重启后前端列表为空、
     * 已建流程无法触发 —— 属用户可直接感知的数据丢失（历史缺陷实证）。定义属用户产物，
     * 按工程边界本就应落用户本机。</p>
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowRepository.class)
    public WorkflowRepository workflowRepository(WorkflowProperties properties,
                                                 ObjectMapper objectMapper,
                                                 ObjectProvider<WorkspaceDirs> workspaceDirsProvider) {
        Path dir = resolveStorageDir(properties, workspaceDirsProvider);
        if (dir == null) {
            log.warn("未解析到工作流存储目录（storage-dir 为空且无 WorkspaceDirs），"
                    + "工作流定义将仅存于内存、重启即丢。");
            return new InMemoryWorkflowRepository();
        }
        log.info("工作流定义落盘目录：{}", dir);
        return new FileWorkflowRepository(dir, objectMapper);
    }

    /** 运行实例仓储：与定义同根，实例落在 {@code <storageDir>/instances} 子目录。 */
    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository.class)
    public WorkflowInstanceRepository workflowInstanceRepository(WorkflowProperties properties,
                                                                 ObjectMapper objectMapper,
                                                                 ObjectProvider<WorkspaceDirs> workspaceDirsProvider) {
        Path dir = resolveStorageDir(properties, workspaceDirsProvider);
        if (dir == null) {
            return new InMemoryWorkflowInstanceRepository();
        }
        return new FileWorkflowInstanceRepository(dir.resolve("instances"), objectMapper);
    }

    /** 解析存储根目录；配置优先，其次框架必备目录；两者皆无返回 {@code null}。 */
    private Path resolveStorageDir(WorkflowProperties properties,
                                   ObjectProvider<WorkspaceDirs> workspaceDirsProvider) {
        String configured = properties.getStorageDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        WorkspaceDirs dirs = workspaceDirsProvider.getIfAvailable();
        return (dirs == null) ? null : dirs.workflowDir();
    }

    // ---------------- 线程资源 ----------------

    @Bean(name = "workflowAsyncExecutor", destroyMethod = "shutdown")
    public ExecutorService workflowAsyncExecutor(WorkflowProperties properties) {
        int size = Math.max(1, properties.getExecution().getThreadPoolSize());
        return Executors.newFixedThreadPool(size, daemonFactory("workflow-async"));
    }

    @Bean(name = "workflowSchedulerExecutor", destroyMethod = "shutdown")
    public ScheduledExecutorService workflowSchedulerExecutor() {
        return Executors.newScheduledThreadPool(2, daemonFactory("workflow-scheduler"));
    }

    // ---------------- 执行器与触发器 ----------------

    @Bean
    public List<NodeExecutor> workflowNodeExecutors(MappingEvaluator mappingEvaluator) {
        List<NodeExecutor> executors = new ArrayList<>();
        executors.add(new StartNodeExecutor());
        executors.add(new EndNodeExecutor());
        executors.add(new LlmNodeExecutor(mappingEvaluator));
        executors.add(new ToolNodeExecutor());
        executors.add(new ConditionNodeExecutor());
        executors.add(new CodeNodeExecutor(mappingEvaluator));
        executors.add(new SubflowNodeExecutor());
        return executors;
    }

    @Bean
    public List<Trigger> workflowTriggers(WorkflowProperties properties,
                                          @Qualifier("workflowSchedulerExecutor") ScheduledExecutorService scheduler) {
        List<Trigger> triggers = new ArrayList<>();
        triggers.add(new ManualTrigger());
        if (properties.getExecution().isAutoTriggers()) {
            triggers.add(new IntervalTrigger(scheduler));
        }
        return triggers;
    }

    // ---------------- 引擎 / 调度 / 服务 / 通道 ----------------

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEngine workflowEngine(DagCompiler compiler,
                                         WorkflowStateMachine stateMachine,
                                         MappingEvaluator mappingEvaluator,
                                         ConditionEvaluator conditionEvaluator,
                                         WorkflowEventBus eventBus,
                                         List<NodeExecutor> nodeExecutors,
                                         LlmAdapter llmAdapter,
                                         ToolAdapter toolAdapter,
                                         SandboxAdapter sandboxAdapter,
                                         WorkflowRepository repository,
                                         WorkflowInstanceRepository instanceRepository,
                                         @Qualifier("workflowAsyncExecutor") ExecutorService asyncExecutor) {
        return new WorkflowEngine(compiler, stateMachine, mappingEvaluator, conditionEvaluator, eventBus,
                nodeExecutors, llmAdapter, toolAdapter, sandboxAdapter, repository, instanceRepository, asyncExecutor);
    }

    /**
     * LangGraph4j 引擎（MVP）：由配置 {@code lucky.workflow.execution.engine=langgraph} 启用。
     *
     * <p>两个引擎实现同一执行契约（{@code run/compile/invoke}），映射到同一 {@link WorkflowService}
     * 与事件总线，故切换对 REST/SSE 与前端完全透明。默认仍为 legacy，待灰度验证后再切换默认值。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "lucky.workflow.execution.engine", havingValue = "langgraph")
    public LangGraphWorkflowEngine langGraphWorkflowEngine(DagCompiler compiler,
                                                           WorkflowStateMachine stateMachine,
                                                           MappingEvaluator mappingEvaluator,
                                                           ConditionEvaluator conditionEvaluator,
                                                           WorkflowEventBus eventBus,
                                                           List<NodeExecutor> nodeExecutors,
                                                           LlmAdapter llmAdapter,
                                                           ToolAdapter toolAdapter,
                                                           SandboxAdapter sandboxAdapter,
                                                           WorkflowRepository repository,
                                                           WorkflowInstanceRepository instanceRepository,
                                                           @Qualifier("workflowAsyncExecutor") ExecutorService asyncExecutor) {
        return new LangGraphWorkflowEngine(compiler, stateMachine, mappingEvaluator, conditionEvaluator, eventBus,
                nodeExecutors, llmAdapter, toolAdapter, sandboxAdapter, repository, instanceRepository, asyncExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowScheduler workflowScheduler(WorkflowEngine engine, List<Trigger> triggers) {
        return new WorkflowScheduler(engine, triggers);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowService workflowService(WorkflowRepository repository,
                                           WorkflowInstanceRepository instanceRepository,
                                           WorkflowEngine engine,
                                           DagCompiler compiler,
                                           WorkflowScheduler scheduler) {
        return new WorkflowService(repository, instanceRepository, engine, compiler, scheduler);
    }

    // 注意：WorkflowController 不在此处以 @Bean 注册——它由 @RestController + 组件扫描装配。
    // 原因见类级 Javadoc：WebFlux 的 RequestMappingHandlerMapping 只认注解，不认 @Bean 返回对象。

    private ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
