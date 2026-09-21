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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工作流 REST 接口 + SSE 执行监控（响应式）。
 *
 * <p><b>必须使用 {@link RestController} + 组件扫描注册（不可改为 @Bean 方式）：</b>
 * WebFlux 的 {@code RequestMappingHandlerMapping.isHandler()} 只认
 * {@code @Controller}/{@code @RequestMapping} 注解（即 {@code AnnotatedElementUtils.hasAnnotation}），
 * <b>不认</b>「由 @Bean 方法返回、但类上无这些注解」的对象。历史缺陷正是
 * 「类上只有 @RequestMapping、由 @Bean 注册」→ 路由不注册 → 请求落静态资源解析
 * → 404 {@code No static resource api/workflows}。故此处与宿主其余 19 个控制器
 * （{@code com.lucky.agent.web.controller.*}）走完全相同的注册路径：注解 + 扫描。</p>
 *
 * <p>SSE 采用 WebFlux 的 {@code Flux<ServerSentEvent>}（而非 Servlet 的 {@code SseEmitter}），
 * 以保持本模块与宿主（响应式栈）一致，且不引入 Tomcat/spring-webmvc 依赖。</p>
 */
@RestController
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

    /** SSE：订阅指定实例的执行事件流（WebFlux 响应式）。 */
    @GetMapping(value = "/instances/{instanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<WorkflowEvent>> events(@PathVariable String instanceId) {
        // 多播 sink：事件总线回调 → Flux 下游；缓冲少量事件以容忍订阅前的瞬时缺口
        Sinks.Many<WorkflowEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        Consumer<WorkflowEvent> listener = event -> {
            if (instanceId.equals(event.instanceId())) {
                sink.tryEmitNext(event);
            }
        };
        eventBus.subscribe(listener);

        return sink.asFlux()
                .map(event -> ServerSentEvent.<WorkflowEvent>builder()
                        .event(event.type().name())
                        .data(event)
                        .build())
                // 心跳：无事件时定期发送注释帧，避免中间层（代理/浏览器）判定连接空闲而断开
                .mergeWith(Flux.interval(Duration.ofSeconds(15))
                        .map(i -> ServerSentEvent.<WorkflowEvent>builder()
                                .comment("keep-alive")
                                .build()))
                .doOnCancel(() -> eventBus.unsubscribe(listener))
                .doOnTerminate(() -> eventBus.unsubscribe(listener))
                .doFinally(signal -> eventBus.unsubscribe(listener));
    }

    // ---------------- 异常映射 ----------------

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowException(WorkflowException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "code", e.getCode()));
    }
}
