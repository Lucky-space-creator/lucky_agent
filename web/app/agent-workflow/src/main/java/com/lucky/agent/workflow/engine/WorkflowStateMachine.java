package com.lucky.agent.workflow.engine;

import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.WorkflowStatus;
import com.lucky.agent.workflow.exception.WorkflowException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工作流状态机：定义并强制工作流实例的状态流转规则。
 *
 * <pre>
 *   DRAFT     → ENABLED | DISABLED
 *   ENABLED   → DISABLED | RUNNING
 *   DISABLED  → ENABLED
 *   RUNNING   → COMPLETED | FAILED | SUSPENDED
 *   SUSPENDED → RUNNING | FAILED
 *   COMPLETED → （终态）
 *   FAILED    → （终态）
 * </pre>
 */
public class WorkflowStateMachine {

    private final Map<WorkflowStatus, Set<WorkflowStatus>> transitions = new EnumMap<>(WorkflowStatus.class);

    public WorkflowStateMachine() {
        transitions.put(WorkflowStatus.DRAFT, EnumSet.of(WorkflowStatus.ENABLED, WorkflowStatus.DISABLED));
        transitions.put(WorkflowStatus.ENABLED, EnumSet.of(WorkflowStatus.DISABLED, WorkflowStatus.RUNNING));
        transitions.put(WorkflowStatus.DISABLED, EnumSet.of(WorkflowStatus.ENABLED));
        transitions.put(WorkflowStatus.RUNNING, EnumSet.of(
                WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.SUSPENDED));
        transitions.put(WorkflowStatus.SUSPENDED, EnumSet.of(WorkflowStatus.RUNNING, WorkflowStatus.FAILED));
        transitions.put(WorkflowStatus.COMPLETED, EnumSet.noneOf(WorkflowStatus.class));
        transitions.put(WorkflowStatus.FAILED, EnumSet.noneOf(WorkflowStatus.class));
    }

    public boolean canTransition(WorkflowStatus from, WorkflowStatus to) {
        if (from == to) {
            return true;
        }
        return transitions.getOrDefault(from, Set.of()).contains(to);
    }

    /** 应用状态流转；非法流转抛异常。 */
    public void transition(WorkflowInstance instance, WorkflowStatus to, String error) {
        WorkflowStatus from = instance.getStatus();
        if (!canTransition(from, to)) {
            throw new WorkflowException("非法状态流转: " + from + " -> " + to);
        }
        switch (to) {
            case COMPLETED -> instance.markCompleted();
            case FAILED -> instance.markFailed(error);
            case SUSPENDED -> instance.markSuspended();
            default -> instance.setStatus(to);
        }
    }

    /** 便捷重载：无错误信息。 */
    public void transition(WorkflowInstance instance, WorkflowStatus to) {
        transition(instance, to, null);
    }
}
