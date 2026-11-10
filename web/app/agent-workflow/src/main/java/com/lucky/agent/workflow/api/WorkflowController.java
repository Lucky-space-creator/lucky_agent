package com.lucky.agent.workflow.api;

import com.lucky.agent.workflow.domain.WorkflowDef;
import com.lucky.agent.workflow.domain.WorkflowInstance;
import com.lucky.agent.workflow.dto.NodeTypeMeta;
import com.lucky.agent.workflow.dto.TriggerRequest;
import com.lucky.agent.workflow.event.WorkflowEvent;
import com.lucky.agent.workflow.event.WorkflowEventBus;
import com.lucky.agent.workflow.event.WorkflowEventType;
import com.lucky.agent.workflow.exception.WorkflowException;
import com.lucky.agent.workflow.service.NodeTypeCatalog;
import com.lucky.agent.workflow.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

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
@Slf4j
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

    /**
     * 节点类型元数据：前端画布的组件面板与属性表单据此动态渲染。
     *
     * <p>与 {@code /instances} 同理，字面量路径优先于 {@code /{id}} 匹配，
     * 不会被 {@link #get(String)} 吞掉。</p>
     */
    @GetMapping("/node-types")
    public List<NodeTypeMeta> nodeTypes() {
        return NodeTypeCatalog.catalog();
    }

    // ---------------- 触发与运行 ----------------

    /**
     * 触发一次执行。
     *
     * <p><b>为什么返回 {@link Mono} 而不是裸对象</b>（台账 #20）：WebFlux 会在
     * {@code reactor-http-nio-N} 事件循环线程上调用控制器方法，而 {@code RunMode.SYNC}
     * 会在<b>调用线程</b>里同步跑完整条工作流 —— 其中 CODE/EXEC 节点还要
     * {@code Mono.block(timeout)} 等命令执行结果。在非阻塞线程上 {@code block()} 直接抛
     * {@code IllegalStateException}，被节点执行器吞成「超时」→ CODE 节点 100% 失败；
     * 即便是 LLM 节点，同步 HTTP 也会把事件循环占死，整个服务失去响应能力。</p>
     *
     * <p>这里显式下沉到 {@link Schedulers#boundedElastic()}：它<b>在语义上允许阻塞</b>，
     * 且队列无界、按需扩线程，正是「跑一段同步/阻塞逻辑」的指定载体。返回体仍是同一个
     * {@link WorkflowInstance} JSON，前端契约不变（只是响应改为在订阅完成后发出）。</p>
     */
    @PostMapping("/{id}/trigger")
    public Mono<WorkflowInstance> trigger(@PathVariable String id,
                                          @RequestBody(required = false) TriggerRequest request) {
        TriggerRequest req = (request == null) ? TriggerRequest.empty() : request;
        return Mono.fromCallable(() -> service.trigger(id, req.variables(), req.resolveMode()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/instances")
    public List<WorkflowInstance> instances() {
        return service.instances();
    }

    @GetMapping("/instances/{instanceId}")
    public WorkflowInstance instance(@PathVariable String instanceId) {
        return service.instance(instanceId);
    }

    /**
     * SSE：订阅指定实例的执行事件流（WebFlux 响应式）。
     *
     * <p><b>终止语义</b>（台账 #36）：实例进入终态（{@code WORKFLOW_COMPLETED} /
     * {@code WORKFLOW_FAILED}）时，事件流以 complete 收口 —— 此前流永不结束，
     * 前端 {@code EventSource} 只能靠页面刷新/手动断开收场。心跳用
     * {@code takeUntil} 联动终止：{@code mergeWith(interval)} 里 interval 是无限源，
     * 不截断的话事件源 complete 了流也不会结束。</p>
     */
    @GetMapping(value = "/instances/{instanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<WorkflowEvent>> events(@PathVariable String instanceId) {
        // 多播 sink：事件总线回调 → Flux 下游。
        // ⚠️ multicast() 不补发历史事件！订阅之前 tryEmitNext 的事件对后到的订阅者是丢失的
        // （注释里若写「缓冲以容忍订阅前缺口」是错的：要补发必须用 replay().limit(n) 或自建环形缓冲）。
        // 这正是前端必须「订阅 + 立即拉一次实例快照 + 兜底轮询」的原因 —— 快照是事实源，事件只是推进信号。
        Sinks.Many<WorkflowEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        Consumer<WorkflowEvent> listener = event -> {
            if (!instanceId.equals(event.instanceId())) {
                return;
            }
            Sinks.EmitResult result = sink.tryEmitNext(event);
            // FAIL_ZERO_SUBSCRIBER 是建连窗口内的正常丢弃（快照兜底），不值一条日志；
            // 其余失败（慢客户端溢出 / 已终止后迟到）必须留痕，否则「推进信号断流」查不到原因。
            if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.warn("SSE 事件投递失败（type={} result={}）：客户端过慢或连接已终止，该事件丢失", event.type(), result);
            }
            if (isTerminal(event.type())) {
                sink.tryEmitComplete(); // 终态后收口：前端 EventSource 收到 close，连接自然结束
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
                // takeUntil：终态事件先放行给订阅者，随后连同无限心跳源一起终止整条流
                // （心跳帧无 data，必须判空，否则终态判定对不上、流永不结束）
                .takeUntil(sse -> sse.data() != null && isTerminal(sse.data().type()))
                .doOnCancel(() -> eventBus.unsubscribe(listener))
                .doOnTerminate(() -> eventBus.unsubscribe(listener))
                .doFinally(signal -> eventBus.unsubscribe(listener));
    }

    /** 是否终态事件（SSE 流以它收口）。 */
    private static boolean isTerminal(WorkflowEventType type) {
        return type == WorkflowEventType.WORKFLOW_COMPLETED || type == WorkflowEventType.WORKFLOW_FAILED;
    }

    // ---------------- 异常映射 ----------------

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowException(WorkflowException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "code", e.getCode()));
    }
}
