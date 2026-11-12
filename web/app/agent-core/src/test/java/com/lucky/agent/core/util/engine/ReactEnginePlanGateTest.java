package com.lucky.agent.core.util.engine;

import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.config.PresetResolver;
import com.lucky.agent.core.util.compact.CompactionPipeline;
import com.lucky.agent.core.util.compact.TokenMeter;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.util.memory.MemoryPrefetcher;
import com.lucky.agent.core.util.metrics.MetricsCollector;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.memory.config.MemoryMdProperties;
import com.lucky.agent.memory.service.MemoryRetriever;
import com.lucky.agent.memory.support.md.HierarchyMemoryRetriever;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.model.support.prompt.BasePromptStore;
import com.lucky.agent.model.support.prompt.RuleStore;
import com.lucky.agent.persona.service.PersonaService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划阶段工具闸回归测试。
 *
 * <p>缺陷现场：PLAN 阶段带着全套工具，模型把一个工程目录逐层 file.list，12 步（PLAN 的步数上限）
 * 全耗在穷举上，最后界面上是一条「已达最大步数（12）仍未产出结论」+ 一屏「查看的文件（52）」
 * （其实全是目录）。修法：PLAN 阶段一律不执行工具，直接回灌拒绝；连续两轮仍在调工具即放弃规划，
 * 交编排层降级为「无需拆分，直接执行」，且不再发 error 事件。</p>
 */
class ReactEnginePlanGateTest {

    private static final String SID = "s-plan-gate";

    private ModelRouter modelRouter;
    private ToolGateway toolGateway;
    private ConversationStateManager stateManager;
    private ConversationStateManager.SessionState state;
    private ChatModel chatModel;
    private PresetResolver presetResolver;
    private AgentEventPublisher publisher;

    private final List<AgentEvent> events = new ArrayList<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        modelRouter = mock(ModelRouter.class);
        toolGateway = mock(ToolGateway.class);
        stateManager = mock(ConversationStateManager.class);
        state = mock(ConversationStateManager.SessionState.class);
        chatModel = mock(ChatModel.class);
        presetResolver = mock(PresetResolver.class);
        publisher = new AgentEventPublisher();

        when(state.messages()).thenReturn(new CopyOnWriteArrayList<ChatMessage>());
        when(state.cancelRequested()).thenReturn(false);
        when(stateManager.session(any())).thenReturn(state);
        when(stateManager.publisher()).thenReturn(publisher);
        when(modelRouter.resolveStreaming(any())).thenReturn(null);
        when(modelRouter.resolve(any())).thenReturn(chatModel);
        when(modelRouter.modelName(any())).thenReturn("test-model");
        when(toolGateway.buildToolSpecifications(anyString(), any())).thenReturn(List.of());
        // 订阅先建立：AgentEventPublisher 在无订阅者时把事件暂存，不会进 events
        publisher.stream(SID).subscribe(events::add);
    }

    /** 规划阶段调用工具：不执行、不产生 action 事件，连续两轮后放弃规划并以 plan-empty 返回。 */
    @Test
    void planPhaseNeverExecutesToolsAndGivesUpInsteadOfBurningAllSteps() {
        // 两轮都只回工具调用（模型在 PLAN 里穷举目录的真实行为）
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return toolCallResponse("file.list", "{\"path\":\"blade-gateway/src/main\"}");
        });

        var result = engine().run(ctx(), Phase.PLAN, "审查代码").block();

        assertEquals("plan-empty", result.status(), "放弃规划应返回 plan-empty，交编排层降级直接执行");
        assertNull(result.error(), "规划无产出不是致命错误，不能带 error（否则会弹出误导性的失败提示）");
        verify(toolGateway, never()).dispatchAll(any(), any());
        assertEquals(ReactEnginePlanGateTest.limit(), requests.size(), "最多容忍两轮工具调用即收口");
        assertFalse(events.stream().anyMatch(e -> "action".equals(e.type())),
                "PLAN 阶段被驳回的工具调用不能发出 action 事件（否则前端会渲染成「查看的文件」）");
        assertFalse(events.stream().anyMatch(e -> "error".equals(e.type())),
                "PLAN 收口不能发 error 事件");
        assertTrue(events.stream().anyMatch(e -> "progress".equals(e.type())),
                "应给出可归因的进度提示");
    }

    /** 回灌给模型的拒绝文案必须说清「不能调工具」，否则模型会换个工具继续试。 */
    @Test
    void rejectionIsFedBackToModelAsToolResult() {
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return toolCallResponse("file.read", "{\"path\":\"pom.xml\"}");
        });

        engine().run(ctx(), Phase.PLAN, "审查代码").block();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, times(2)).chat(captor.capture());
        List<ChatRequest> sent = captor.getAllValues();
        ChatRequest second = sent.get(1);
        String fedBack = second.messages().stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)
                .map(m -> ((dev.langchain4j.data.message.ToolExecutionResultMessage) m).text())
                .findFirst().orElse("");
        assertTrue(fedBack.contains("规划阶段禁止调用工具"),
                "驳回结果要作为工具结果回灌，模型才知道该改用文字回答，实际=" + fedBack);
    }

    /** 模型第一轮就守规矩（不调工具）：正常返回计划文本，不受工具闸影响。 */
    @Test
    void planWithoutToolCallsStaysUntouched() {
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"goal\":\"x\",\"steps\":[]}"))
                    .build();
        });

        var result = engine().run(ctx(), Phase.PLAN, "审查代码").block();

        assertEquals("success", result.status());
        assertTrue(result.finalText().contains("steps"));
        verify(toolGateway, never()).dispatchAll(any(), any());
    }

    /**
     * 执行阶段步数耗尽：不再直接报错，而是用一次「无工具调用」把已有信息收成结论。
     *
     * <p>缺陷现场：一轮已经读了/列了十几处，步数用尽后整轮被丢弃，用户只收到一条
     * 「已达最大步数（12）仍未产出结论」。</p>
     */
    @Test
    void actStepExhaustionSalvagesAnswerInsteadOfFailing() {
        when(presetResolver.actMaxSteps()).thenReturn(1);
        when(toolGateway.dispatchAll(any(), any()))
                .thenReturn(List.of(ToolResult.ok("[目录] src\n[文件] pom.xml")));
        // 第 1 次：调工具（步数用尽）；第 2 次：收尾调用，直接给结论
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            if (requests.size() == 1) {
                return toolCallResponse("file.list", "{\"path\":\"\"}");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("## 结论\n工程为多模块结构，已列出根目录。"))
                    .build();
        });

        var result = engine().run(ctx(), Phase.ACT, "看看工程结构").block();

        assertEquals("success", result.status(), "拿到收尾结论就应正常结束，而不是 max-steps");
        assertTrue(result.finalText().contains("结论"), "收尾调用产出的正文要作为最终回答");
        assertNull(result.error());
        ChatRequest salvageRequest = requests.get(1);
        assertTrue(salvageRequest.toolSpecifications() == null || salvageRequest.toolSpecifications().isEmpty(),
                "收尾调用必须不带工具，否则模型会继续穷举而不是总结");
        assertFalse(publisher != null && events.stream().anyMatch(e -> "error".equals(e.type())),
                "收尾成功就不该再发 error 事件");
    }

    /* ---------- helpers ---------- */
    private static int limit() {
        // 与引擎常量保持一致：容忍两轮工具调用
        return 2;
    }

    private static ChatResponse toolCallResponse(String tool, String args) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-" + tool)
                .name(tool)
                .arguments(args)
                .build();
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder().text("").toolExecutionRequests(List.of(req)).build())
                .build();
    }

    private ConversationCtx ctx() {
        return ConversationCtx.builder()
                .sessionRef(SessionRef.of(SID, "u1", "w1"))
                .phase(Phase.PLAN)
                .goal("审查代码")
                .extra(new java.util.HashMap<>())
                .build();
    }

    private ReactEngine engine() {
        return new ReactEngine(
                modelRouter,
                toolGateway,
                mock(PersonaService.class),
                workspaceConfig(),
                mock(MemoryRetriever.class),
                mock(HierarchyMemoryRetriever.class),
                mock(MemoryPrefetcher.class),
                mock(MemoryMdProperties.class),
                stateManager,
                mock(CoreProperties.class),
                mock(CompactionPipeline.class),
                mock(TokenMeter.class),
                mock(LifecycleHookDispatcher.class),
                mock(MetricsCollector.class),
                new StepLimitGuard(12, 30),
                basePromptStore(),
                mock(RuleStore.class),
                presetResolver,
                0.9);
    }

    private BasePromptStore basePromptStore() {
        BasePromptStore store = mock(BasePromptStore.class);
        when(store.basePrompt()).thenReturn("base");
        return store;
    }

    /** WorkspaceConfig#getWorkspace 必须返回 Optional（引擎直接 isPresent()，null 会 NPE）。 */
    private WorkspaceConfig workspaceConfig() {
        WorkspaceConfig cfg = mock(WorkspaceConfig.class);
        when(cfg.getWorkspace(any())).thenReturn(Optional.empty());
        return cfg;
    }
}
