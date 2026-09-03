package com.lucky.agent.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务进度（供 Web/CLI 对话界面展示复杂任务拆分清单）。
 *
 * <p>进度数据仅来自 core 的 PLAN/子代理执行事件，本模块只被动接收、不反向调用 core。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
public class TaskProgress {

    /** 小任务状态。 */
    public enum TaskStatus {
        PENDING("pending"),
        RUNNING("running"),
        DONE("done"),
        FAILED("failed"),
        ASK("ask");

        private final String code;

        TaskStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @JsonProperty("sessionId")
    private final String sessionId;
    @JsonProperty("tasks")
    private final List<TaskItem> tasks = new ArrayList<>();
    @JsonProperty("done")
    private int done;
    @JsonProperty("total")
    private int total;

    public TaskProgress(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 小任务项。
     *
     * @param taskId 任务 ID
     * @param title  任务标题
     * @param status 状态
     */
    public record TaskItem(String taskId, String title, TaskStatus status) {
    }

    public void reset(List<TaskItem> items) {
        tasks.clear();
        tasks.addAll(items);
        total = items.size();
        done = (int) items.stream().filter(t -> t.status() == TaskStatus.DONE).count();
    }

    public void update(String taskId, TaskStatus status) {
        for (int i = 0; i < tasks.size(); i++) {
            TaskItem item = tasks.get(i);
            if (item.taskId().equals(taskId)) {
                tasks.set(i, new TaskItem(item.taskId(), item.title(), status));
                break;
            }
        }
        done = (int) tasks.stream().filter(t -> t.status() == TaskStatus.DONE).count();
    }

    /** 完成比例（0~100），无任务时返回 0。 */
    public int percent() {
        return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
    }
}
