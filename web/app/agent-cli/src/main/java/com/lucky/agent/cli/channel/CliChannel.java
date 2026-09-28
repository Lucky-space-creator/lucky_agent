package com.lucky.agent.cli.channel;

import com.lucky.agent.common.contract.AgentChannel;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.RunResult;
import com.lucky.agent.common.dto.SessionRef;
import com.lucky.agent.common.dto.SessionSnapshot;
import com.lucky.agent.common.dto.UserInput;
import com.lucky.agent.core.service.ConversationManager;
import com.lucky.agent.core.util.runtime.ConversationStateManager;
import com.lucky.agent.core.util.runtime.SessionCancellation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CLI 通道：实现 {@link AgentChannel} 契约，与 Web 通道消费同一事件流、同一会话状态源。
 *
 * <p>通道<b>只负责渲染与输入，不持有业务逻辑</b>：所有状态查询走 {@link ConversationStateManager}，
 * 不自行缓存会话（此前 CLI 在通道内用 {@code volatile boolean pendingConfirm} 暂存确认结果，
 * 属契约明令禁止的「通道自行缓存会话状态」，已随本次改造移除）。</p>
 *
 * <p><b>渲染器是会话级注入物</b>：终端是在运行时才建立的（依赖 JLine / TTY 探测），
 * 无法在 Spring 装配期确定，故由 {@code CliRunner} 在建立终端后调用
 * {@link #attachRenderer(EventSink)} 注入；未注入时渲染为空操作，保证契约方法可独立单测。</p>
 */
@Component
public class CliChannel implements AgentChannel {

    private final ConversationManager conversationManager;
    private final ConversationStateManager stateManager;

    private volatile EventSink renderer;

    public CliChannel(ConversationManager conversationManager, ConversationStateManager stateManager) {
        this.conversationManager = conversationManager;
        this.stateManager = stateManager;
    }

    /** 注入终端渲染器（由 CliRunner 在建立终端后调用）。 */
    public void attachRenderer(EventSink renderer) {
        this.renderer = renderer;
    }

    @Override
    public Flux<AgentEvent> subscribe(SessionRef session) {
        return stateManager.publisher().stream(session.sessionId());
    }

    @Override
    public Mono<RunResult> submit(SessionRef session, UserInput input) {
        return conversationManager.submit(session, input);
    }

    /**
     * 取消当前运行。
     *
     * <p>与 {@code WebChannel#cancel} 共用同一内核原语 {@link SessionCancellation#cancel}：
     * 置取消标志 → 补发 {@code stop} 收尾 → 销毁 sink。通道不再各自实现取消逻辑。</p>
     */
    @Override
    public Mono<Void> cancel(SessionRef session) {
        return Mono.fromRunnable(() -> SessionCancellation.cancel(stateManager, session));
    }

    @Override
    public Mono<SessionSnapshot> snapshot(SessionRef session) {
        return Mono.just(stateManager.snapshot(session));
    }

    /**
     * 渲染一条事件并采集本轮现场（订阅回调内调用）。
     *
     * @param event   事件
     * @param capture 本轮采集器（可为 null，仅渲染）
     */
    public void onEvent(AgentEvent event, TurnCapture capture) {
        if (capture != null) {
            capture.add(event);
        }
        EventSink r = this.renderer;
        if (r != null) {
            r.render(event);
        }
    }

    /** 本轮收尾（补换行、刷新缓冲）。 */
    public void endOfTurn() {
        EventSink r = this.renderer;
        if (r != null) {
            r.endOfTurn();
        }
    }

    /** 收尾时补一条本地提示（不来自内核事件，仅终端反馈）。 */
    public void localNote(String text) {
        EventSink r = this.renderer;
        if (r != null && text != null) {
            r.render(AgentEvent.progress("local", text));
        }
    }
}
