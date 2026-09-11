package com.lucky.agent.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.contract.LifecycleHook;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.PlanValidator;
import com.lucky.agent.core.util.compact.CompactionPipeline;
import com.lucky.agent.core.util.compact.FiveLevelCompactionPipeline;
import com.lucky.agent.core.util.compact.TokenMeter;
import com.lucky.agent.core.util.engine.EarlyStopPolicy;
import com.lucky.agent.core.util.engine.StepLimitGuard;
import com.lucky.agent.core.util.gateway.ObservationNormalizer;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.core.util.hook.ExternalHookManager;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.util.planactask.ActScheduler;
import com.lucky.agent.core.util.planactask.AskSuspender;
import com.lucky.agent.core.util.planactask.PlanGenerator;
import com.lucky.agent.core.util.planactask.Replanner;
import com.lucky.agent.core.util.subagent.ResultAggregator;
import com.lucky.agent.core.util.subagent.SubAgentExecutor;
import com.lucky.agent.core.util.subagent.SubAgentFactory;
import com.lucky.agent.core.util.subagent.TaskDecomposer;
import com.lucky.agent.core.util.subagent.TaskProgressTracker;
import com.lucky.agent.core.util.subagent.TaskScheduler;
import com.lucky.agent.core.util.memory.MemoryPrefetcher;
import com.lucky.agent.core.util.verify.CommandVerifier;
import com.lucky.agent.core.util.verify.FileVerifier;
import com.lucky.agent.core.util.verify.LlmJudgeVerifier;
import com.lucky.agent.core.util.verify.ObjectiveVerifier;
import com.lucky.agent.core.util.verify.VerificationChain;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * agent-core 配置：引擎支撑组件装配。
 */
@Configuration
@EnableConfigurationProperties(CoreProperties.class)
public class CoreModuleConfig {

    /** 压缩流水线（D16 五级：Microcompact / Snip / Reactive + preservedSegment + 熔断）。 */
    @Bean
    public CompactionPipeline compactionPipeline() {
        return new FiveLevelCompactionPipeline(1200, 6, 1500, 3);
    }

    /** Token 计量器（§6 / §4.13）。 */
    @Bean
    public TokenMeter tokenMeter() {
        return new TokenMeter();
    }

    /** Observation 归一化。 */
    @Bean
    public ObservationNormalizer observationNormalizer() {
        return new ObservationNormalizer();
    }

    /** 内部 + 外部 Hook 分发器（deny-wins，按各自 order 合并排序）。 */
    @Bean
    public LifecycleHookDispatcher lifecycleHookDispatcher(List<LifecycleHook> hooks,
                                                           ExternalHookManager externalHookManager) {
        List<LifecycleHook> all = new ArrayList<>(hooks == null ? List.of() : hooks);
        all.addAll(externalHookManager.hooks());
        return new LifecycleHookDispatcher(all);
    }

    /** 计划校验器。 */
    @Bean
    public PlanValidator planValidator() {
        return new PlanValidator();
    }

    /** 客观验证器：文件存在性 / 内容断言（流程图 D 节点）。 */
    @Bean
    public FileVerifier fileVerifier(FileService fileService, CoreProperties coreProperties) {
        return new FileVerifier(fileService, coreProperties.verificationTimeoutSec());
    }

    /** 客观验证器：校验命令退出码（构建 / 测试 / 状态码 / 数据库，需 FULL 权限）。 */
    @Bean
    public CommandVerifier commandVerifier(FileService fileService, CoreProperties coreProperties) {
        return new CommandVerifier(fileService, coreProperties.verificationTimeoutSec());
    }

    /** 主观判定器：客观信号缺失时的兜底整体判定（轻量直连模型，不经引擎，见 P0-3）。 */
    @Bean
    public LlmJudgeVerifier llmJudgeVerifier(ModelRouter modelRouter) {
        return new LlmJudgeVerifier(modelRouter);
    }

    /** 客观验证责任链：客观优先，客观未通过覆盖主观「已达成」结论。 */
    @Bean
    public VerificationChain verificationChain(FileVerifier fileVerifier,
                                               CommandVerifier commandVerifier,
                                               LlmJudgeVerifier llmJudgeVerifier,
                                               CoreProperties coreProperties) {
        List<ObjectiveVerifier> verifiers = List.of(fileVerifier, commandVerifier);
        return new VerificationChain(verifiers, llmJudgeVerifier, coreProperties);
    }

    /** 计划生成（MVP：解析模型 PLAN 产出为结构化 Plan）。 */
    @Bean
    public PlanGenerator planGenerator() {
        return new PlanGenerator();
    }

    /** 重规划器。 */
    @Bean
    public Replanner replanner(Engine engine) {
        return new Replanner(engine);
    }

    /** ACT 阶段调度器。 */
    @Bean
    public ActScheduler actScheduler(Engine engine) {
        return new ActScheduler(engine);
    }

    /** 步数上限守卫（PLAN/ACT 阶段步数硬上限，防无限试错）。 */
    @Bean
    public StepLimitGuard stepLimitGuard(CoreProperties properties) {
        return new StepLimitGuard(properties.planMaxSteps(), properties.actMaxSteps());
    }

    /** 早停策略：客观验证通过率低于阈值转 ASK（低置信早停）。 */
    @Bean
    public EarlyStopPolicy earlyStopPolicy(CoreProperties properties) {
        return new EarlyStopPolicy(properties.earlyStopConfidenceThreshold());
    }

    /** ASK 挂起器（D7，本地持久化）。 */
    @Bean
    public AskSuspender askSuspender(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        return new AskSuspender(dirs, objectMapper);
    }

    /** 任务分解（MVP：启发式）。 */
    @Bean
    public TaskDecomposer taskDecomposer() {
        return new TaskDecomposer();
    }

    /** 子代理结果聚合。 */
    @Bean
    public ResultAggregator resultAggregator() {
        return new ResultAggregator();
    }

    /** 任务进度追踪（小任务列表 + 进度事件）。 */
    @Bean
    public TaskProgressTracker coreTaskProgressTracker() {
        return new TaskProgressTracker();
    }

    /** 子代理工厂。 */
    @Bean
    public SubAgentFactory subAgentFactory(ModelRouter modelRouter, ToolGateway toolGateway) {
        return new SubAgentFactory(modelRouter, toolGateway);
    }

    /** 子代理执行器（P2-2：注入工作空间配置用于权限级别继承 + 核心配置用于任务超时）。 */
    @Bean
    public SubAgentExecutor subAgentExecutor(SubAgentFactory subAgentFactory,
                                             LifecycleHookDispatcher lifecycleHookDispatcher,
                                             ToolGateway toolGateway,
                                             WorkspaceConfig workspaceConfig,
                                             CoreProperties coreProperties) {
        return new SubAgentExecutor(subAgentFactory, lifecycleHookDispatcher, toolGateway,
                workspaceConfig, coreProperties);
    }

    /** 子代理调度器（串行 + 并行 fan-out/fan-in）。 */
    @Bean
    public TaskScheduler subAgentTaskScheduler(SubAgentExecutor subAgentExecutor,
                                               ResultAggregator resultAggregator,
                                               CoreProperties coreProperties) {
        return new TaskScheduler(subAgentExecutor, resultAggregator, coreProperties);
    }

    /** 记忆预取选择器（Claude 记忆预取等价物：索引 → TopN 条目，会话内去重）。 */
    @Bean
    public MemoryPrefetcher memoryPrefetcher(ModelRouter modelRouter,
                                             HierarchyMemoryRetriever hierarchyMemoryRetriever,
                                             MemoryMdProperties props) {
        return new MemoryPrefetcher(modelRouter, hierarchyMemoryRetriever, props);
    }
}
