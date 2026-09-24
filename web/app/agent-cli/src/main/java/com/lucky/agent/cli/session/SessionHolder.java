package com.lucky.agent.cli.session;

import com.lucky.agent.common.dto.SessionRef;

/**
 * 当前会话的可变持有者。
 *
 * <p>REPL 的会话会在运行期被 {@code /new}、{@code /resume}、{@code /workspace} 换掉，
 * 而通道、轮次执行器、状态栏都需要「当前是哪个会话」。用一个持有者对象统一表达，
 * 比在各处传 id、或在多处各存一份要可靠 —— 后者会出现「渲染用的是 A 会话、提交去了 B 会话」这类
 * 极难定位的串号问题。</p>
 *
 * <p>注意：持有的是<b>定位符</b>，不是会话内容。会话内容始终由内核
 * {@code ConversationStateManager} 持有（通道不自行缓存会话，契约 §5）。</p>
 */
public final class SessionHolder {

    private volatile SessionRef ref;

    public SessionHolder(SessionRef initial) {
        this.ref = initial;
    }

    public SessionRef ref() {
        return ref;
    }

    public void ref(SessionRef next) {
        this.ref = next;
    }

    public String sessionId() {
        return ref.sessionId();
    }

    public String userId() {
        return ref.userId();
    }

    public String workspaceId() {
        return ref.workspaceId();
    }

    /** 换一个工作空间（保留会话 ID，用于 {@code /workspace} 之外的场景）。 */
    public void workspaceId(String workspaceId) {
        this.ref = new SessionRef(ref.sessionId(), ref.userId(), workspaceId);
    }

    /** 会话 ID 短形式（状态栏展示）。 */
    public String shortId() {
        String id = ref.sessionId();
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }
}
