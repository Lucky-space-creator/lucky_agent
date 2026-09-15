package com.lucky.agent.web.controller;

import com.lucky.agent.core.util.subagent.TaskScheduler;
import com.lucky.agent.core.repository.TaskSchedulerStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 子代理调度状态接口。
 */
@RestController
@RequestMapping("/api/subagents")
public class SubAgentStatusController {

    private final TaskScheduler taskScheduler;

    public SubAgentStatusController(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /** 调度器运行状态快照（在途批次/并行度/累计完成失败）。 */
    @GetMapping("/status")
    public TaskSchedulerStatus status() {
        return taskScheduler.status();
    }
}
