package com.lucky.agent.common.dto;

/**
 * 会话定位符（契约 §7）。
 *
 * <p>所有会话、任务、事件均以 {@link SessionRef} 定位，禁止使用裸路径或裸 sessionId 跨模块传递。</p>
 *
 * @param sessionId   会话 ID（uuid）
 * @param userId      用户 ID（uuid）
 * @param workspaceId 工作空间 ID（uuid）
 */
public record SessionRef(String sessionId, String userId, String workspaceId) {

    public static SessionRef of(String sessionId, String userId, String workspaceId) {
        return new SessionRef(sessionId, userId, workspaceId);
    }
}
