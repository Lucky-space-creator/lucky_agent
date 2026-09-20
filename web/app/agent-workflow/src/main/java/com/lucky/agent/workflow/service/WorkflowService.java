package com.lucky.agent.workflow.service;

import com.lucky.agent.workflow.domain.VariableScope;
import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.domain.enums.RunMode;
import com.lucky.agent.workflow.engine.CompiledWorkflow;
import com.lucky.agent.workflow.engine.DagCompiler;
import com.lucky.agent.workflow.engine.WorkflowEngine;
import com.lucky.agent.workflow.engine.trigger.WorkflowScheduler;
import com.lucky.agent.workflow.exception.WorkflowException;
import com.lucky.agent.workflow.repository.WorkflowInstanceRepository;
import com.lucky.agent.workflow.repository.WorkflowRepository;

import java.util.List;
import java.util.Map;

/**
 * 工作流应用服务：定义 CRUD、启停、编译校验、触发执行与运行实例查询。
 * <p>作为 REST/CLI/程序化调用的统一入口，屏蔽引擎与仓储细节（通道解耦）。</p>
 */
public class WorkflowService {

    private final WorkflowRepository repository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowEngine engine;
    private final DagCompiler compiler;
    private final WorkflowScheduler scheduler;

    public WorkflowService(WorkflowRepository repository,
                           WorkflowInstanceRepository instanceRepository,
                           WorkflowEngine engine,
                           DagCompiler compiler,
                           WorkflowScheduler scheduler) {
        this.repository = repository;
        this.instanceRepository = instanceRepository;
        this.engine = engine;
        this.compiler = compiler;
        this.scheduler = scheduler;
    }

    // ---------------- 定义管理 ----------------

    public WorkflowDef create(WorkflowDef definition) {
        if (repository.existsById(definition.id())) {
            throw new WorkflowException("工作流已存在: " + definition.id());
        }
        compiler.compile(definition); // 静态校验：结构 + 环检测
        WorkflowDef saved = repository.save(definition.withCreatedNow());
        registerIfEnabled(saved);
        return saved;
    }

    public WorkflowDef update(WorkflowDef definition) {
        repository.findById(definition.id())
                .orElseThrow(() -> new WorkflowException("工作流不存在: " + definition.id()));
        compiler.compile(definition);
        WorkflowDef saved = repository.save(definition.withUpdatedNow());
        reRegister(saved);
        return saved;
    }

    public List<WorkflowDef> list() {
        return repository.findAll();
    }

    public WorkflowDef get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new WorkflowException("工作流不存在: " + id));
    }

    public void delete(String id) {
        WorkflowDef def = get(id);
        unregister(def);
        repository.deleteById(id);
    }

    public WorkflowDef setEnabled(String id, boolean enabled) {
        WorkflowDef saved = repository.save(get(id).withEnabled(enabled));
        if (enabled) {
            registerIfEnabled(saved);
        } else {
            unregister(saved);
        }
        return saved;
    }

    // ---------------- 编译校验 ----------------

    public CompiledWorkflow validate(WorkflowDef definition) {
        return compiler.compile(definition);
    }

    // ---------------- 触发与运行 ----------------

    public WorkflowInstance trigger(String id, Map<String, Object> variables, RunMode mode) {
        WorkflowDef def = get(id);
        return engine.run(def, new VariableScope(variables), mode == null ? RunMode.SYNC : mode);
    }

    public WorkflowInstance instance(String instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new WorkflowException("实例不存在: " + instanceId));
    }

    public List<WorkflowInstance> instances() {
        return instanceRepository.findAll();
    }

    // ---------------- 内部 ----------------

    private void registerIfEnabled(WorkflowDef def) {
        if (scheduler != null && def.enabled()) {
            scheduler.register(def);
        }
    }

    private void unregister(WorkflowDef def) {
        if (scheduler != null) {
            scheduler.unregister(def);
        }
    }

    private void reRegister(WorkflowDef def) {
        if (scheduler == null) {
            return;
        }
        scheduler.unregister(def);
        if (def.enabled()) {
            scheduler.register(def);
        }
    }
}
