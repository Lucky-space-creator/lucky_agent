package com.lucky.agent.core.service;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.core.config.CoreProperties;
import com.lucky.agent.core.models.PlanValidator;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.runtime.RuntimeSession;
import com.lucky.agent.core.runtime.RuntimeSessionFactory;
import com.lucky.agent.core.runtime.budget.BudgetScope;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.gateway.ModelGateway;
import com.lucky.agent.core.runtime.middleware.MiddlewareContext;
import com.lucky.agent.core.util.planactask.PlanGenerator;
import com.lucky.agent.core.util.planactask.Replanner;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * langgraph 主环的取消语义（台账 #18）。
 *
 * <p>修复前该编排器里 {@code cancelRequested} 出现 0 次：用户点停止只置了一个标志位，
 * 编排照常跑满 {@code orchestrator-max-iterations} 轮 —— 期间仍会派发工具、写文件。
 * 本用例用「零模型调用」这个否定式断言守住修复：一旦取消检查被挪到模型调用之后，
 * {@code verifyNoInteractions(modelGateway)} 立即失败。</p>
 */
class LangGraphCancelTest {

    private static final SessionRef REF = new SessionRef("s-cancel", "u-1", "ws-1");

    private ConversationCtx ctx() {
        return ConversationCtx.builder()
                .sessionRef(REF)
                .phase(Phase.ACT)
                .permissionLevel(PermissionLevel.FULL)
                .goal("做点会写文件的事")
                .extra(Map.of())
                .build();
    }

    private CoreProperties props() {
        return new CoreProperties(12, 30, 30, -1, false, 4, 300,
                5, 3, "langgraph", Boolean.TRUE, 120, 500, 0.3);
    }

    private LangGraphOrchestrator orchestrator(ConversationStateManager stateManager,
                                               RuntimeSessionFactory sessions,
                                               ModelGateway modelGateway) {
        return new LangGraphOrchestrator(modelGateway, stateManager,
                new PlanGenerator(), new PlanValidator(), new Replanner(null),
                null, null, null, sessions, props(), null, null);
    }

    private RuntimeSessionFactory sessionsBoundTo(RuntimeSession session) {
        RuntimeSessionFactory factory = mock(RuntimeSessionFactory.class);
        when(factory.open(any(), any(), any(), any())).thenReturn(session);
        return factory;
    }

    private RuntimeSession sessionWithTokens(long usedTokens) {
        BudgetScope global = mock(BudgetScope.class);
        when(global.usedTokens()).thenReturn(usedTokens);
        return new RuntimeSession(global, mock(RuntimeContext.class),
                mock(MiddlewareContext.class), 5);
    }

    @Test
    @DisplayName("#18 已请求停止时，主环不得发起任何模型调用，直接以 cancelled 收尾")
    void cancelledSessionStopsBeforeAnyModelCall() {
        ConversationStateManager stateManager = mock(ConversationStateManager.class);
        ConversationStateManager.SessionState state = mock(ConversationStateManager.SessionState.class);
        when(state.cancelRequested()).thenReturn(true);
        when(stateManager.session(any())).thenReturn(state);

        ModelGateway modelGateway = mock(ModelGateway.class);
        LangGraphOrchestrator orchestrator = orchestrator(stateManager,
                sessionsBoundTo(sessionWithTokens(0L)), modelGateway);

        EngineRunResult result = orchestrator.run(REF, ctx(), mock(AgentEventPublisher.class));

        assertThat(result.status())
                .as("取消必须走 cancelled 分支，而不是跑满轮次后返回 max_iterations")
                .isEqualTo("cancelled");
        verifyNoInteractions(modelGateway);
    }

    @Test
    @DisplayName("#18 取消时仍如实回传已消耗 token，供上层记账")
    void cancelledRunReportsConsumedTokens() {
        ConversationStateManager stateManager = mock(ConversationStateManager.class);
        ConversationStateManager.SessionState state = mock(ConversationStateManager.SessionState.class);
        when(state.cancelRequested()).thenReturn(true);
        when(stateManager.session(any())).thenReturn(state);

        LangGraphOrchestrator orchestrator = orchestrator(stateManager,
                sessionsBoundTo(sessionWithTokens(1234L)), mock(ModelGateway.class));

        EngineRunResult result = orchestrator.run(REF, ctx(), mock(AgentEventPublisher.class));

        assertThat(result.tokenUsed())
                .as("中途取消不能把已花掉的 token 归零")
                .isEqualTo(1234L);
    }
}
