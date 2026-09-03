package com.lucky.agent.workspace.progress;

import com.lucky.agent.workspace.api.dto.TaskProgress;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务进度追踪（§4.8 任务分解进度）。
 *
 * <p>以订阅方式跟踪 core 的 PLAN/子代理执行事件，维护小任务列表、状态与完成比例；
 * 仅被动接收进度事件，不反向调用 core，避免循环依赖。</p>
 */
@Service
public class TaskProgressTracker {

    private final Map<String, TaskProgress> progressBySession = new ConcurrentHashMap<>();

    /** 会话任务开始：记录小任务清单。 */
    public void begin(String sessionId, java.util.List<TaskProgress.TaskItem> items) {
        TaskProgress progress = new TaskProgress(sessionId);
        progress.reset(items);
        progressBySession.put(sessionId, progress);
    }

    /** 更新某小任务状态。 */
    public void update(String sessionId, String taskId, TaskProgress.TaskStatus status) {
        TaskProgress progress = progressBySession.get(sessionId);
        if (progress != null) {
            progress.update(taskId, status);
        }
    }

    /** 查询会话进度快照。 */
    public Optional<TaskProgress> snapshot(String sessionId) {
        return Optional.ofNullable(progressBySession.get(sessionId));
    }

    /** 清理会话进度（会话结束时调用）。 */
    public void remove(String sessionId) {
        progressBySession.remove(sessionId);
    }
}
