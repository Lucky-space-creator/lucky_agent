package com.lucky.agent.workflow.repository;

import com.lucky.agent.workflow.domain.WorkflowDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现（默认）：进程内保存工作流定义，适合开发与无持久化场景。 */
public class InMemoryWorkflowRepository implements WorkflowRepository {

    private final ConcurrentHashMap<String, WorkflowDef> store = new ConcurrentHashMap<>();

    @Override
    public WorkflowDef save(WorkflowDef definition) {
        store.put(definition.id(), definition);
        return definition;
    }

    @Override
    public Optional<WorkflowDef> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<WorkflowDef> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public boolean deleteById(String id) {
        return store.remove(id) != null;
    }
}
