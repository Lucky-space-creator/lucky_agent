package com.lucky.agent.workflow.repository;

import com.lucky.agent.workflow.domain.WorkflowInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现：保存运行实例（默认，保留最近运行记录）。 */
public class InMemoryWorkflowInstanceRepository implements WorkflowInstanceRepository {

    private final ConcurrentHashMap<String, WorkflowInstance> store = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowInstance instance) {
        store.put(instance.getInstanceId(), instance);
    }

    @Override
    public Optional<WorkflowInstance> findById(String instanceId) {
        return Optional.ofNullable(store.get(instanceId));
    }

    @Override
    public List<WorkflowInstance> findAll() {
        List<WorkflowInstance> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparingLong(WorkflowInstance::getStartedAt).reversed());
        return list;
    }
}
