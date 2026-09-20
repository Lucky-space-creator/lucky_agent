package com.lucky.agent.workflow.api;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.dto.TriggerRequest;
import com.lucky.agent.workflow.event.WorkflowEvent;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.exception.WorkflowException;
import com.lucky.agent.workflow.service.WorkflowService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工作流 REST 接口 + SSE 执行监控。
 * <p>注意：本类<b>不使用</b> @RestController 注解，仅以 @RequestMapping 标注为 MVC 处理器，
 * 由 {@code WorkflowAutoConfiguration} 以 @Bean 方式注册——从而不依赖宿主的组件扫描范围，
 * 也不会与宿主扫描产生重复注册。</p>
 */
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService service;
    private final WorkflowEventBus eventBus;

    public WorkflowController(WorkflowService service, WorkflowEventBus eventBus) {
        this.service = service;
        this.eventBus = eventBus;
    }

    // ---------------- 定义管理 ----------------

    @PostMapping
    public WorkflowDef create(@RequestBody WorkflowDef definition) {
        return service.create(definition);
    }

    @GetMapping
    public List<WorkflowDef> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public WorkflowDef get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public WorkflowDef update(@PathVariable String id, @RequestBody WorkflowDef definition) {
        WorkflowDef merged = new WorkflowDef(id, definition.name(), definition.description(),
                definition.version(), definition.nodes(), definition.edges(),
                definition.trigger(), definition.enabled(),
                definition.createdAt(), definition.updatedAt());
        return service.update(merged);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enabled")
    public WorkflowDef setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        return service.setEnabled(id, enabled);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody WorkflowDef definition) {
        var compiled = service.validate(definition);
        return Map.of(
                "valid", true,
                "startNodeId", compiled.startNodeId(),
                "nodeCount", compiled.nodes().size(),
                "topologicalOrder", compiled.topologicalOrder());
    }

    // ---------------- 触发与运行 ----------------

    @PostMapping("/{id}/trigger")
    public WorkflowInstance trigger(@PathVariable String id,
                                    @RequestBody(required = false) TriggerRequest request) {
        TriggerRequest req = (request == null) ? TriggerRequest.empty() : request;
        return service.trigger(id, req.variables(), req.mode());
    }

    @GetMapping("/instances")
    public List<WorkflowInstance> instances() {
        return service.instances();
    }

    @GetMapping("/instances/{instanceId}")
    public WorkflowInstance instance(@PathVariable String instanceId) {
        return service.instance(instanceId);
    }

    /** SSE：订阅指定实例的执行事件流。 */
    @GetMapping(value = "/instances/{instanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String instanceId) {
        SseEmitter emitter = new SseEmitter(0L);
        Consumer<WorkflowEvent> listener = event -> {
            if (instanceId.equals(event.instanceId())) {
                try {
                    emitter.send(SseEmitter.event().name(event.type().name()).data(event));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
        };
        eventBus.subscribe(listener);
        emitter.onCompletion(() -> eventBus.unsubscribe(listener));
        emitter.onTimeout(() -> eventBus.unsubscribe(listener));
        emitter.onError(t -> eventBus.unsubscribe(listener));
        return emitter;
    }

    // ---------------- 异常映射 ----------------

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowException(WorkflowException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "code", e.getCode()));
    }
}
