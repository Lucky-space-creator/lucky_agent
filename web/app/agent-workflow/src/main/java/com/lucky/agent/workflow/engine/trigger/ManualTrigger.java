package com.lucky.agent.workflow.engine.trigger;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.enums.TriggerType;

/**
 * 手动触发器：不自行调度，仅由 API/UI 显式触发（{@code POST /api/workflows/{id}/trigger}）。
 */
public class ManualTrigger implements Trigger {

    @Override
    public boolean supports(TriggerType type) {
        return type == TriggerType.MANUAL;
    }

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public void schedule(WorkflowDef definition, Runnable task) {
        // 手动触发无需调度
    }

    @Override
    public void cancel(String workflowId) {
        // no-op
    }
}
