package core.util.subagent;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.SubAgentSpec;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.repository.SubAgentResult;
import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.core.util.hook.LifecycleHookDispatcher;
import com.lucky.agent.core.util.subagent.SubAgentExecutor;
import com.lucky.agent.core.util.subagent.SubAgentFactory;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子代理工具隔离（台账 #15）。
 *
 * <p>修复前：{@code disallowedTools} 只过滤「给模型看的 {@code toolSpecifications}」，
 * 而 {@code SubAgentExecutor} 分发时走 {@code ToolGateway.dispatch} —— 它在<b>全量</b>工具表
 * 按名字查找。模型幻觉或被注入一个未声明工具仍会真的执行。</p>
 *
 * <p>本测试锁三件事：① 白名单计算正确；② 未授权工具<b>不分发</b>（dispatch 零调用）；
 * ③ 拒绝后仍回灌 tool 结果（否则下一轮请求缺 tool 响应直接 400）。</p>
 */
class SubAgentToolIsolationTest {

    private static final String WID = "w1";

    // ---------- 白名单计算 ----------

    @Test
    @DisplayName("disallowedTools 从允许集合中剔除")
    void disallowedToolsAreRemoved() {
        SubAgentSpec spec = SubAgentSpec.builder()
                .id("a").name("a").disallowedTools(List.of("shell.exec")).build();

        Set<String> allowed = factory().allowedToolNames(
                specs("file.read", "file.write", "shell.exec"), spec);

        assertThat(allowed).containsExactlyInAnyOrder("file.read", "file.write");
    }

    @Test
    @DisplayName("tools 白名单把能力面收敛到指定项，且 disallowedTools 优先")
    void toolsLimitsToDeclaredSet() {
        SubAgentSpec spec = SubAgentSpec.builder()
                .id("a").name("a")
                .tools(List.of("file.read", "shell.exec"))
                .disallowedTools(List.of("shell.exec"))
                .build();

        Set<String> allowed = factory().allowedToolNames(
                specs("file.read", "file.write", "shell.exec"), spec);

        assertThat(allowed).containsExactly("file.read");
    }

    @Test
    @DisplayName("声明一个根本不存在的工具名不会凭空获得权限")
    void unknownDeclaredToolIsNotGranted() {
        SubAgentSpec spec = SubAgentSpec.builder()
                .id("a").name("a").tools(List.of("shell.exec")).build();

        Set<String> allowed = factory().allowedToolNames(specs("file.read"), spec);

        assertThat(allowed).isEmpty();
    }

    @Test
    @DisplayName("运行时 allows() 对未授权工具名与 null 返回 false")
    void runtimeAllowsRejectsUnknown() {
        SubAgentFactory.SubAgentRuntime runtime = runtime(Set.of("file.read"), mock(ChatModel.class), 3);

        assertThat(runtime.allows("file.read")).isTrue();
        assertThat(runtime.allows("shell.exec")).isFalse();
        assertThat(runtime.allows(null)).isFalse();
    }

    // ---------- 执行器行为 ----------

    @Test
    @DisplayName("模型请求未授权工具时不分发，且仍回灌 tool 结果不让下一轮 400")
    void unauthorizedToolIsNotDispatched() {
        ChatModel model = mock(ChatModel.class);
        ToolGateway gateway = mock(ToolGateway.class);
        SubAgentFactory factory = mock(SubAgentFactory.class);
        WorkspaceConfig workspaceConfig = mock(WorkspaceConfig.class);
        when(workspaceConfig.permissionLevelOf(WID)).thenReturn(Optional.of(PermissionLevel.MODIFY));

        when(factory.create(any(), anyString(), anyString()))
                .thenReturn(runtime(Set.of("file.read"), model, 3));

        // 第 1 轮：模型幻觉出一个被禁的工具；第 2 轮：模型接受拒绝并给出结论
        ToolExecutionRequest forbidden = ToolExecutionRequest.builder()
                .id("call-1").name("shell.exec").arguments("{\"command\":\"rm -rf /\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(forbidden)))
                .thenReturn(response(AiMessage.from("已放弃危险操作，结论如下：…")));

        SubAgentExecutor executor = new SubAgentExecutor(factory, new LifecycleHookDispatcher(List.of()),
                gateway, workspaceConfig, properties());

        SubAgentResult result = executor.execute(spec(), "do it", WID, "sess-1").block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("结论");
        // 关键断言：未授权工具从未进入分发（也就没碰到执行臂）
        verify(gateway, never()).dispatch(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("授权工具照常分发")
    void authorizedToolIsDispatched() {
        ChatModel model = mock(ChatModel.class);
        ToolGateway gateway = mock(ToolGateway.class);
        SubAgentFactory factory = mock(SubAgentFactory.class);
        WorkspaceConfig workspaceConfig = mock(WorkspaceConfig.class);
        when(workspaceConfig.permissionLevelOf(WID)).thenReturn(Optional.of(PermissionLevel.MODIFY));

        when(factory.create(any(), anyString(), anyString()))
                .thenReturn(runtime(Set.of("file.read"), model, 3));
        when(gateway.dispatch(eq("file.read"), anyMap(), any()))
                .thenReturn(ToolResult.ok("file content"));

        ToolExecutionRequest authorized = ToolExecutionRequest.builder()
                .id("call-1").name("file.read").arguments("{\"path\":\"a.txt\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(authorized)))
                .thenReturn(response(AiMessage.from("读到了")));

        SubAgentExecutor executor = new SubAgentExecutor(factory, new LifecycleHookDispatcher(List.of()),
                gateway, workspaceConfig, properties());

        SubAgentResult result = executor.execute(spec(), "read it", WID, "sess-1").block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        verify(gateway).dispatch(eq("file.read"), anyMap(), any());
    }

    // ---------- 辅助 ----------

    private static SubAgentFactory factory() {
        return new SubAgentFactory(mock(ModelRouter.class), mock(ToolGateway.class));
    }

    private static SubAgentSpec spec() {
        return SubAgentSpec.builder().id("sa").name("sa").maxTurns(3).build();
    }

    private static SubAgentFactory.SubAgentRuntime runtime(Set<String> allowed, ChatModel model, int maxTurns) {
        return new SubAgentFactory.SubAgentRuntime(model,
                allowed.stream().map(SubAgentToolIsolationTest::specOf).toList(), allowed, "sys", maxTurns);
    }

    private static List<ToolSpecification> specs(String... names) {
        return Arrays.stream(names).map(SubAgentToolIsolationTest::specOf).toList();
    }

    private static ToolSpecification specOf(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static ChatResponse response(AiMessage ai) {
        return ChatResponse.builder().aiMessage(ai).build();
    }

    private static CoreProperties properties() {
        return new CoreProperties(12, 30, 30, 1_000_000L, true, 4, 300L,
                5, 2, "thin", true, 60L, 1000L, 0.3);
    }
}
