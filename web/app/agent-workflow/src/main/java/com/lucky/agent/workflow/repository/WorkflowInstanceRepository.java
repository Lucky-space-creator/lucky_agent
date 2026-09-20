package com.lucky.agent.workflow.repository;

import com.lucky.agent.workflow.domain.WorkflowInstance;

import java.util.List;
import java.util.Optional;

/** 工作流实例仓储（运行记录）。 */
public interface WorkflowInstanceRepository {

    void save(WorkflowInstance instance);

    Optional<WorkflowInstance> findById(String instanceId);

    List<WorkflowInstance> findAll();
}
