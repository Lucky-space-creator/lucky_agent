package com.lucky.agent.core.runtime;

import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.runtime.gateway.CircuitBreakerModelGateway;
import com.lucky.agent.core.runtime.gateway.EngineModelGateway;
import com.lucky.agent.core.runtime.gateway.ModelGateway;
import com.lucky.agent.core.runtime.gateway.RetryModelGateway;
import com.lucky.agent.core.runtime.loop.AgentRuntime;
import com.lucky.agent.core.runtime.loop.ExecutorSubAgentLauncher;
import com.lucky.agent.core.runtime.loop.LoopController;
import com.lucky.agent.core.runtime.loop.SubAgentLauncher;
import com.lucky.agent.core.runtime.loop.SubAgentPool;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import com.lucky.agent.core.runtime.memory.NoopMemoryPort;
import com.lucky.agent.core.runtime.memory.SpringMemoryPort;
import com.lucky.agent.core.runtime.middleware.BudgetMiddleware;
import com.lucky.agent.core.runtime.middleware.CompactionMiddleware;
import com.lucky.agent.core.runtime.middleware.LoggingMiddleware;
import com.lucky.agent.core.runtime.middleware.MemoryRecallMiddleware;
import com.lucky.agent.core.runtime.middleware.Middleware;
import com.lucky.agent.core.runtime.middleware.PermissionMiddleware;
import com.lucky.agent.core.runtime.middleware.RetryMiddleware;
import com.lucky.agent.core.runtime.middleware.StateSnapshotMiddleware;
import com.lucky.agent.core.runtime.plan.HeuristicStepDecomposer;
import com.lucky.agent.core.runtime.plan.StepDecomposer;
import com.lucky.agent.core.runtime.strategy.DefaultStrategyPlugin;
import com.lucky.agent.core.runtime.strategy.StrategyPlugin;
import com.lucky.agent.core.runtime.tool.DecomposeTool;
import com.lucky.agent.core.runtime.tool.RuntimeTool;
import com.lucky.agent.core.runtime.tool.SpawnSubAgentTool;
import com.lucky.agent.core.runtime.tool.ToolRegistry;
import com.lucky.agent.core.runtime.verify.ChainVerifierAdapter;
import com.lucky.agent.core.runtime.verify.CompositeVerifier;
import com.lucky.agent.core.runtime.verify.ExternalCommandVerifier;
import com.lucky.agent.core.runtime.verify.FileArtifactVerifier;
import com.lucky.agent.core.runtime.verify.Verifier;
import com.lucky.agent.core.util.subagent.SubAgentExecutor;
import com.lucky.agent.core.util.verify.VerificationChain;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.memory.support.md.MarkdownMemoryWriter;
import com.lucky.agent.permission.service.PermissionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时装配（厚运行时的 Spring 装配点）。
 *
 * <p>把「可插拔组件」全部注册为 Bean，{@link AgentRuntime} 通过 {@code List<T>} 自动聚合：
 * 新增一个中间件/工具/策略只需声明对应类型的 Bean，无需改动主循环或运行时。</p>
 *
 * <p>全部默认 Bean 都带 {@link ConditionalOnMissingBean}：宿主可逐个替换（例如换用真实
 * LLM 判定、接入 Redis 记忆、放宽权限阈值），也可整体替换 {@link Verifier}/{@link MemoryPort}。</p>
 *
 * <p><b>装配常开</b>：本配置不随 {@code orchestrator-mode} 开关——因为 {@code @Component}
 * 形态的工具（如 {@code VerifyTool}/{@code MemoryTool}）依赖这里的 {@link Verifier}/{@link MemoryPort}，
 * 若随模式关闭会导致默认 reactor 模式启动失败。运行时组件本身是惰性的（不启动即为死代码），
 * 常驻装配零副作用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {

    /** 触发上下文压缩的累计 token 阈值（跨过即压缩并保留结构化字段）。 */
    private static final long COMPACTION_TOKEN_THRESHOLD = 60_000L;

    /** 子代理最大嵌套深度（根为 0）。 */
    private static final int SUB_AGENT_MAX_DEPTH = 2;

    /** 每轮最多派发的子代理数（防止一次拆解刷出大量子代理）。 */
    private static final int SUB_AGENT_MAX_SPAWN_PER_RUN = 4;

    /** 状态快照保留条数。 */
    private static final int SNAPSHOT_CAPACITY = 64;

    /** 模型端点连续失败阈值：达到即熔断。 */
    private static final int MODEL_FAILURE_THRESHOLD = 5;

    /** 熔断冷却时长（毫秒）：冷却后放行一个半开探测。 */
    private static final long MODEL_OPEN_COOLDOWN_MS = 30_000L;

    // ==================== 基础设施 ====================

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SubAgentPool subAgentPool(CoreProperties properties) {
        // 并发度取 core.subagentMaxConcurrency，虚拟线程执行
        return new SubAgentPool(properties.subagentMaxConcurrency(), SUB_AGENT_MAX_DEPTH);
    }

    @Bean
    @ConditionalOnMissingBean
    public StepDecomposer stepDecomposer() {
        return new HeuristicStepDecomposer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(List<RuntimeTool> tools) {
        return new ToolRegistry(tools);
    }

    // ==================== 记忆（桥接既有分层 Markdown 记忆） ====================

    @Bean
    @ConditionalOnMissingBean(MemoryPort.class)
    public MemoryPort memoryPort(ObjectProvider<MarkdownMemoryWriter> writerProvider,
                                 ObjectProvider<HierarchyMemoryRetriever> retrieverProvider) {
        MarkdownMemoryWriter writer = writerProvider.getIfAvailable();
        HierarchyMemoryRetriever retriever = retrieverProvider.getIfAvailable();
        if (writer == null && retriever == null) {
            // 记忆能力未装配时降级：保证薄主循环可独立运行
            return new NoopMemoryPort();
        }
        return new SpringMemoryPort(writer, retriever);
    }

    // ==================== 验证（复用既有 VerificationChain，客观优先） ====================

    @Bean
    @ConditionalOnMissingBean(Verifier.class)
    public Verifier verifier(ObjectProvider<VerificationChain> chainProvider, CoreProperties properties) {
        List<Verifier> chain = new ArrayList<>();
        VerificationChain verificationChain = chainProvider.getIfAvailable();
        if (verificationChain != null) {
            // 客观优先：文件/命令校验走既有 FileService（受权限与执行臂边界约束）+ LLM 结构化判定
            chain.add(new ChainVerifierAdapter(verificationChain));
        }
        // 轻量客观校验：不依赖 FileService，适用于无工作区的场景
        chain.add(new FileArtifactVerifier());
        chain.add(new ExternalCommandVerifier(properties.verificationTimeoutSec() * 1000, null));
        return new CompositeVerifier(chain);
    }

    // ==================== 工具（LLM 可调用） ====================

    @Bean
    @ConditionalOnMissingBean
    public DecomposeTool decomposeTool(StepDecomposer decomposer) {
        return new DecomposeTool(decomposer);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubAgentLauncher subAgentLauncher(ObjectProvider<SubAgentExecutor> executorProvider,
                                             SubAgentPool subAgentPool, CoreProperties properties) {
        return new ExecutorSubAgentLauncher(executorProvider.getIfAvailable(), subAgentPool,
                SUB_AGENT_MAX_DEPTH, SUB_AGENT_MAX_SPAWN_PER_RUN,
                properties.subagentTaskTimeoutSec() * 1000);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpawnSubAgentTool spawnSubAgentTool(SubAgentLauncher launcher) {
        return new SpawnSubAgentTool(launcher);
    }

    // ==================== 中间件（横切关注点，按 order 排序） ====================

    @Bean
    @ConditionalOnMissingBean
    public PermissionMiddleware permissionMiddleware(ToolRegistry toolRegistry,
                                                     ObjectProvider<PermissionService> permissionService) {
        return new PermissionMiddleware(toolRegistry, permissionService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public BudgetMiddleware budgetMiddleware() {
        return new BudgetMiddleware();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryMiddleware retryMiddleware(ToolRegistry toolRegistry, CoreProperties properties) {
        // 总尝试次数 = 1 次首发 + orchestratorMaxRetries 次重试
        return new RetryMiddleware(toolRegistry, properties.orchestratorMaxRetries() + 1,
                properties.retryBackoffMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionMiddleware compactionMiddleware(MemoryPort memoryPort) {
        return new CompactionMiddleware(memoryPort, COMPACTION_TOKEN_THRESHOLD);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryRecallMiddleware memoryRecallMiddleware(MemoryPort memoryPort) {
        return new MemoryRecallMiddleware(memoryPort);
    }

    @Bean
    @ConditionalOnMissingBean
    public StateSnapshotMiddleware stateSnapshotMiddleware() {
        return new StateSnapshotMiddleware(SNAPSHOT_CAPACITY);
    }

    @Bean
    @ConditionalOnMissingBean
    public LoggingMiddleware loggingMiddleware() {
        return new LoggingMiddleware();
    }

    // ==================== 策略 ====================

    @Bean
    @ConditionalOnMissingBean
    public DefaultStrategyPlugin defaultStrategyPlugin(CoreProperties properties) {
        // 至少 2 个拆解信号才拆；拆出 >=2 步且含并行意图才派子代理
        return new DefaultStrategyPlugin(2, 2, properties.subagentEnabled());
    }

    // ==================== 模型调用网关（重试退避 + 熔断） ====================

    /**
     * 模型调用网关装饰链：{@code CircuitBreaker( Retry( Engine ) )}。
     *
     * <p>由外到内分别是：熔断（快速失败，避免持续等超时）→ 重试退避（仅瞬时故障）→ 真实引擎调用。
     * 两个主循环（thin / langgraph）与 {@code ActScheduler} 全部经此调用模型，
     * 因此模型侧韧性<b>只实现一次</b>。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ModelGateway.class)
    public ModelGateway modelGateway(Engine engine, CoreProperties properties) {
        ModelGateway gateway = new EngineModelGateway(engine);
        gateway = new RetryModelGateway(gateway,
                properties.orchestratorMaxRetries() + 1, properties.retryBackoffMs());
        return new CircuitBreakerModelGateway(gateway, MODEL_FAILURE_THRESHOLD, MODEL_OPEN_COOLDOWN_MS);
    }

    // ==================== 运行时会话 ====================

    @Bean
    @ConditionalOnMissingBean
    public RuntimeSessionFactory runtimeSessionFactory(CoreProperties properties) {
        return new RuntimeSessionFactory(properties);
    }

    // ==================== 循环控制与运行时 ====================

    @Bean
    @ConditionalOnMissingBean
    public LoopController loopController(MemoryPort memoryPort, CoreProperties properties) {
        return new LoopController(memoryPort, properties.orchestratorMaxIterations(),
                properties.earlyStopConfidenceThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(List<Middleware> middleware,
                                     ToolRegistry toolRegistry,
                                     List<StrategyPlugin> strategies,
                                     Verifier verifier,
                                     MemoryPort memoryPort,
                                     SubAgentPool subAgentPool) {
        return new AgentRuntime(middleware, toolRegistry, strategies, verifier, memoryPort, subAgentPool);
    }
}
