package com.lucky.agent.core.util.runtime;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;

/**
 * 会话取消的内核原语：把「取消一轮运行」的语义收敛到唯一实现，供各通道
 * （Web / CLI）复用，避免各通道各自复制一份取消逻辑导致行为漂移。
 *
 * <p>取消语义固定为三步（顺序不可调换）：</p>
 * <ol>
 *   <li><b>置取消标志</b>：{@code requestCancel()} 让编排/引擎在循环边界检查并尽早退出，
 *       不再开启新轮次 / 新工具调用（仅移除订阅不会停止执行线程）；</li>
 *   <li><b>补发 stop 收尾</b>：向仍连接的订阅方发一个 {@code stop} 终止事件，
 *       使客户端立即关闭流、不因服务端正常完成而入流重连（否则会立即收到引擎后续增量，
 *       表现成「点了停止还在不断发消息」）；</li>
 *   <li><b>销毁 sink</b>：移除事件发布通道，完成收尾。</li>
 * </ol>
 */
public final class SessionCancellation {

    private SessionCancellation() {
    }

    /**
     * 取消指定会话的当前运行（幂等，允许会话不存在）。
     *
     * @param stateManager 会话状态源
     * @param session      会话引用
     */
    public static void cancel(ConversationStateManager stateManager, SessionRef session) {
        stateManager.find(session.sessionId()).ifPresent(s -> s.requestCancel());
        AgentEventPublisher publisher = stateManager.publisher();
        publisher.publish(session.sessionId(),
                AgentEvent.stop(session.sessionId(), "cancelled", "已取消，停止本轮执行。"));
        publisher.remove(session.sessionId());
    }
}