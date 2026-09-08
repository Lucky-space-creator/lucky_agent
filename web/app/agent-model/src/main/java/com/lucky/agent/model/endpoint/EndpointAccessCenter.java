package com.lucky.agent.model.endpoint;

import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.model.api.ModelEndpoint;
import com.lucky.agent.model.api.dto.AgentSettings;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.lucky.agent.model.config.ModelConfigStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 端点接入中心：管理本机端点配置并适配为 LangChain4j ChatModel。
 * <p>配置经 ModelConfigStore 明文落盘于用户工作空间；端点列表驻留内存。单模型退化为直通。</p>
 */

@Slf4j
@Component
public class EndpointAccessCenter {

    /** 主端点角色码。 */
    private static final String MAIN_ROLE = "main";
    /** 备用端点角色码。 */
    private static final String FALLBACK_ROLE = "fallback";
    /** 记忆管理 Agent 端点角色码（会话记忆总结专用；未配置时回退主力模型）。 */
    private static final String MEMORY_ROLE = "memory";

    private final ModelConfigStore store;
    private final HealthProbe healthProbe;
    private final List<ModelConfig> configs;
    private InferenceDepth inferenceDepth;

    public EndpointAccessCenter(ModelConfigStore store, HealthProbe healthProbe) {
        this.store = store;
        this.healthProbe = healthProbe;
        AgentSettings settings = store.load();
        this.configs = settings.models();
        this.inferenceDepth = settings.inferenceDepth() == null ? InferenceDepth.defaultValue() : settings.inferenceDepth();
        normalizeRoles();
        this.configs.forEach(c -> c.keyConfigured(isConfigured(c.apiKey())));
        log.info("加载模型端点配置，共 {} 个，推理深度 {}", configs.size(), inferenceDepth);
    }

    /**
     * 收敛历史脏数据：多个端点同时为 main 时，仅保留第一个，其余降级为 fallback；
     * memory（记忆管理 Agent）同样保持唯一，避免主端点或记忆端点悄悄换人。
     */
    private void normalizeRoles() {
        boolean mainSeen = false;
        for (ModelConfig config : configs) {
            if (MAIN_ROLE.equals(config.role())) {
                if (mainSeen) {
                    config.role(FALLBACK_ROLE);
                } else {
                    mainSeen = true;
                }
            }
        }
        if (!mainSeen) {
            configs.stream().filter(ModelConfig::enabled).findFirst().ifPresent(c -> c.role(MAIN_ROLE));
        }
        boolean memorySeen = false;
        for (ModelConfig config : configs) {
            if (MEMORY_ROLE.equals(config.role())) {
                if (memorySeen) {
                    config.role(FALLBACK_ROLE);
                } else {
                    memorySeen = true;
                }
            }
        }
    }

    /** 全局推理深度（枚举）。 */
    public synchronized InferenceDepth inferenceDepth() {
        return inferenceDepth;
    }

    /** 更新全局推理深度并落盘；null 视为非法，抛业务异常。 */
    public synchronized void setInferenceDepth(InferenceDepth depth) {
        if (depth == null) {
            throw new AgentException("INVALID_INFERENCE_DEPTH", "推理深度不能为空");
        }
        this.inferenceDepth = depth;
        persist();
    }

    /** 将当前内存配置整体明文落盘（含推理深度）。 */
    private synchronized void persist() {
        store.save(AgentSettings.of(inferenceDepth, configs));
    }

    /** 全部端点配置（复制副本返回，避免外部直接修改内部对象）。 */
    public synchronized List<ModelConfig> list() {
        return configs.stream().map(ModelConfig::copy).toList();
    }

    /** 主端点：优先 role=main 且启用的端点。*/
    public synchronized ModelConfig primary() {
        return configs.stream()
                .filter(ModelConfig::enabled)
                .filter(c -> "main".equals(c.role()))
                .findFirst()
                .orElseGet(() -> configs.stream().filter(ModelConfig::enabled).findFirst().orElse(null));
    }

    /** 备用端点：非主端点、且非记忆专用端点的启用端点。 */
    public synchronized Optional<ModelConfig> fallback() {
        ModelConfig primary = primary();
        return configs.stream()
                .filter(ModelConfig::enabled)
                .filter(c -> !MEMORY_ROLE.equals(c.role()))
                .filter(c -> primary == null || !c.id().equals(primary.id()))
                .findFirst();
    }

    /** 记忆管理 Agent 端点（role=memory 且启用）；未配置返回空，调用方回退主力模型。 */
    public synchronized Optional<ModelConfig> memory() {
        return configs.stream()
                .filter(ModelConfig::enabled)
                .filter(c -> MEMORY_ROLE.equals(c.role()))
                .findFirst();
    }

    /**
     * 保存（新增或更新）端点配置；apiKey 为空视为未修改，保留原 Key 后明文落盘。
     *
     * <p>两个不变量（否则主端点会「静默漂移」）：</p>
     * <ol>
     *     <li><b>保序</b>：更新已有端点时原地替换，不 move-to-end；列表顺序即主端点优先级。</li>
     *     <li><b>唯一主端点</b>：新配置角色为 main 时，把其余 main 降级为 fallback，
     *         避免出现两个 main、由列表顺序隐式决定谁是主端点的局面。</li>
     * </ol>
     */
    public synchronized ModelConfig save(ModelConfig config) {
        if (config.id() == null || config.id().isBlank()) {
            config.id(UUID.randomUUID().toString());
        }
        String incomingKey = config.apiKey();
        if (incomingKey == null || incomingKey.isBlank()) {
            // 前端编辑时 Key 为脱敏空值，保留已有明文 Key，避免误清空
            configs.stream().filter(c -> c.id().equals(config.id()))
                    .findFirst().ifPresent(existing -> config.apiKey(existing.apiKey()));
        }
        if (MAIN_ROLE.equals(config.role())) {
            configs.stream()
                    .filter(c -> !c.id().equals(config.id()))
                    .filter(c -> MAIN_ROLE.equals(c.role()))
                    .forEach(c -> c.role(FALLBACK_ROLE));
        }
        if (MEMORY_ROLE.equals(config.role())) {
            configs.stream()
                    .filter(c -> !c.id().equals(config.id()))
                    .filter(c -> MEMORY_ROLE.equals(c.role()))
                    .forEach(c -> c.role(FALLBACK_ROLE));
        }
        int index = indexOf(config.id());
        if (index >= 0) {
            configs.set(index, config);
        } else {
            configs.add(config);
        }
        config.keyConfigured(isConfigured(config.apiKey()));
        persist();
        healthProbe.probe(config);
        return config;
    }

    private int indexOf(String id) {
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isConfigured(String key) {
        return key != null && !key.isBlank();
    }

    /** 按 id 查找端点，不存在抛出业务异常。 */
    public synchronized ModelConfig require(String id) {
        return configs.stream().filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AgentException("MODEL_NOT_FOUND", "模型端点不存在：" + id));
    }

    /** 将当前内存配置整体明文落盘（批量重填后用）。 */
    public synchronized void saveAll() {
        persist();
    }

    /** 批量更新端点 apiKey（仅更新非空值），返回实际更新数量。 */
    public synchronized int updateKeys(Map<String, String> keyById) {
        int count = 0;
        for (ModelConfig config : configs) {
            String newKey = keyById.get(config.id());
            if (newKey != null && !newKey.isBlank()) {
                config.apiKey(newKey);
                count++;
            }
        }
        if (count > 0) {
            persist();
        }
        return count;
    }

    /** 删除端点配置。*/
    public synchronized boolean delete(String id) {
        boolean removed = configs.removeIf(c -> c.id().equals(id));
        if (removed) {
            persist();
        }
        return removed;
    }

    /** 探活全部启用端点。*/
    public void probeAll() {
        configs.stream().filter(ModelConfig::enabled).forEach(healthProbe::probe);
    }

    /** 探活全部启用端点，返回逐条结构化结果。 */
    public List<HealthProbe.ProbeResult> probeAllResults() {
        return configs.stream().filter(ModelConfig::enabled).map(healthProbe::probeResult).toList();
    }

    /** 按 id 探活已保存端点。 */
    public HealthProbe.ProbeResult probeById(String id) {
        return healthProbe.probeResult(require(id));
    }

    /** 探活未保存的表单配置（不写健康缓存）。 */
    public HealthProbe.ProbeResult probeConfig(ModelConfig config) {
        return healthProbe.probeOnce(config);
    }

    /** 将端点配置适配为 LangChain4j ChatLanguageModel（携带当前推理深度）。 */
    public ModelEndpoint toEndpoint(ModelConfig config) {
        return new ModelEndpointImpl(config, healthProbe, inferenceDepth);
    }

    /** 当前主端点对应的 LangChain4j ChatLanguageModel。*/
    public ModelEndpoint currentEndpoint() {
        ModelConfig primary = primary();
        if (primary == null) {
            throw new AgentException("MODEL_NOT_CONFIGURED", "未配置模型端点，请前往配置");
        }
        return toEndpoint(primary);
    }
}
