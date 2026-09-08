package com.lucky.agent.web.controller;

import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.lucky.agent.model.endpoint.EndpointAccessCenter;
import com.lucky.agent.model.endpoint.HealthProbe;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 模型接入配置接口（本地配置直连，明文存储于用户本机）。
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final EndpointAccessCenter accessCenter;

    public ModelController(EndpointAccessCenter accessCenter) {
        this.accessCenter = accessCenter;
    }

    /**
     * 端点配置列表（<b>Key 已脱敏</b>）。
     *
     * <p>列表接口一律把 {@code apiKey} 置空、只回 {@code keyConfigured} 标记：
     * 历史上会把磁盘上的明文（甚至是失效的旧密文）回填进编辑表单，用户看到「有值」就以为 Key 已就位，
     * 保存时又把这段脏值原样写回。编辑时留空即表示「不修改」，由
     * {@code EndpointAccessCenter#save} 保留原 Key。</p>
     */
    @GetMapping
    public List<ModelConfig> list() {
        return accessCenter.list().stream()
                .map(c -> {
                    c.apiKey("");
                    return c;
                })
                .toList();
    }

    /** 保存（新增或更新）端点配置；apiKey 为空视为未修改，保留原 Key。 */
    @PostMapping
    public ModelConfig save(@RequestBody ModelConfig config) {
        return accessCenter.save(config);
    }

    /**
     * 读取指定端点明文 API Key（仅编辑场景调用）。
     *
     * <p>列表接口保持脱敏（apiKey 恒为空），此处按 id 单独返回明文，供编辑弹窗回显。
     * 本机单人应用、Key 明文落盘于本机 settings.json（零托管），前端仅在用户主动编辑时拉取。</p>
     */
    @GetMapping("/{id}/key")
    public Map<String, String> key(@PathVariable String id) {
        String raw = accessCenter.require(id).apiKey();
        return Map.of("id", id, "apiKey", raw == null ? "" : raw);
    }

    /** 删除端点配置。 */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        return Map.of("removed", accessCenter.delete(id));
    }

    /**
     * 探活端点：请求体可选。
     * <ul>
     *     <li>空 / {@code {}} → 探活全部启用端点</li>
     *     <li>{@code {id}} → 探活指定已保存端点</li>
     *     <li>完整配置（含 endpointUrl）→ 探活未保存的表单配置（测试连接）</li>
     * </ul>
     * 探测为阻塞网络请求，在 boundedElastic 线程执行，避免占用 Reactor 事件循环线程。
     */
    @PostMapping("/probe")
    public Mono<Map<String, Object>> probe(@RequestBody(required = false) ModelConfig body) {
        return Mono.fromCallable(() -> doProbe(body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doProbe(ModelConfig body) {
        List<HealthProbe.ProbeResult> results;
        if (body != null && body.endpointUrl() != null && !body.endpointUrl().isBlank()) {
            results = List.of(accessCenter.probeConfig(body));
        } else if (body != null && body.id() != null && !body.id().isBlank()) {
            results = List.of(accessCenter.probeById(body.id()));
        } else {
            results = accessCenter.probeAllResults();
        }
        boolean ok = results.stream().allMatch(HealthProbe.ProbeResult::healthy);
        return Map.of("ok", ok, "results", results);
    }

    /** 全局推理深度（枚举名，如 BALANCED）。 */
    @GetMapping("/depth")
    public Map<String, InferenceDepth> depth() {
        return Map.of("depth", accessCenter.inferenceDepth());
    }

    /** 更新全局推理深度（枚举名）。 */
    @PutMapping("/depth")
    public Map<String, InferenceDepth> updateDepth(@RequestBody DepthBody body) {
        accessCenter.setInferenceDepth(body.depth());
        return Map.of("depth", accessCenter.inferenceDepth());
    }

    /** 推理深度请求体。 */
    public record DepthBody(InferenceDepth depth) {
    }
}
