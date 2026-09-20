package com.lucky.agent.core.util.subagent;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;

import java.util.List;

/**
 * 任务进度追踪：复杂任务小任务列表与进度事件推送。
 *
 * <p>经 {@code task_plan} / {@code task_progress} 事件实时推送，前端展示清单、
 * 当前步骤与完成比例（与执行步骤一致）。</p>
 */
public class TaskProgressTracker {

    /**
     * 推送任务计划。
     *
     * @param sessionId 会话 ID
     * @param tasks     小任务清单
     * @param publisher 事件发布器
     */
    public void plan(String sessionId, List<AgentEvent.TaskItem> tasks, AgentEventPublisher publisher) {
        publisher.publish(sessionId, AgentEvent.taskPlan(sessionId, tasks));
    }

    /**
     * 推送任务进度。
     *
     * @param sessionId 会话 ID
     * @param taskId    任务 ID
     * @param status    状态
     * @param done      已完成数
     * @param total     总数
     * @param publisher 事件发布器
     */
    public void progress(String sessionId, String taskId, AgentEvent.TaskProgressStatus status,
                         int done, int total, AgentEventPublisher publisher) {
        publisher.publish(sessionId, AgentEvent.taskProgress(sessionId, taskId, status, done, total));
    }
}
