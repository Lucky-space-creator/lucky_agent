package com.lucky.agent.model.service.impl;

import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.model.api.ModelEndpoint;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.model.api.dto.ModelConfig;
import com.lucky.agent.model.api.dto.ModelRouterStatus;
import com.lucky.agent.model.support.endpoint.EndpointAccessCenter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Service;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型路由实现：单模型退化为直通；多模型时主端点失效自动切备用。 */

@Slf4j
@Service
public class ModelRouterImpl implements ModelRouter {

    /** 记忆专用端点角色码（与会话对话模型隔离，防止记忆端点被误选为对话模型）。 */
    private static final String MEMORY_ROLE = "memory";

    private final EndpointAccessCenter accessCenter;

    public ModelRouterImpl(EndpointAccessCenter accessCenter) {
        this.accessCenter = accessCenter;
    }

    @Override
    public ChatModel resolve() {
        ModelConfig primary = accessCenter.primary();
        if (primary == null) {
            throw new AgentException("MODEL_NOT_CONFIGURED", "未配置模型端点，请前往配置");
        }
        ModelEndpoint primaryEndpoint = accessCenter.toEndpoint(primary);
        Optional<ModelConfig> fallback = accessCenter.fallback();
        if (!primaryEndpoint.healthy() && fallback.isPresent()) {
            log.warn("主端点不可达，切换备用端点：{}", fallback.get().name());
            return accessCenter.toEndpoint(fallback.get()).toModel();
        }
        return primaryEndpoint.toModel();
    }

    @Override
    public ChatModel resolve(String modelId) {
        ModelConfig target = configOf(modelId);
        if (target == null) {
            throw new AgentException("MODEL_NOT_CONFIGURED", "未配置模型端点，请前往配置");
        }
        ModelEndpoint targetEndpoint = accessCenter.toEndpoint(target);
        Optional<ModelConfig> fallback = accessCenter.fallback();
        if (!targetEndpoint.healthy() && fallback.isPresent()) {
            log.warn("指定端点不可达，切换备用端点：{}", fallback.get().name());
            return accessCenter.toEndpoint(fallback.get()).toModel();
        }
        return targetEndpoint.toModel();
    }

    @Override
    public String modelName(String modelId) {
        ModelConfig target = configOf(modelId);
        return target == null ? "unknown" : target.modelName();
    }

    @Override
    public StreamingChatModel resolveStreaming(String modelId) {
        ModelConfig target = configOf(modelId);
        if (target == null) {
            return null;
        }
        return accessCenter.toEndpoint(target).toStreamingModel();
    }

    @Override
    public int contextWindow(String modelId) {
        ModelConfig target = configOf(modelId);
        return target == null ? 0 : target.contextWindow();
    }

    /**
     * 定位端点配置：{@code modelId} 为空或已不存在（端点被删除/禁用）时回退主端点，
     * 避免界面上选中了一个已失效的端点导致整轮对话失败。
     *
     * <p>记忆专用端点（role=memory）不作为会话对话模型：即便前端把它的 id 传来
     * （如选择器误选、旧状态残留），也一律回退主端点，杜绝「选了 A 实际在调 B」。</p>
     */
    private ModelConfig configOf(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return accessCenter.primary();
        }
        return accessCenter.list().stream()
                .filter(c -> modelId.equals(c.id()))
                .filter(c -> !MEMORY_ROLE.equals(c.role()))
                .findFirst()
                .orElseGet(accessCenter::primary);
    }

    @Override
    public Optional<ChatModel> resolveFallback() {
        return accessCenter.fallback()
                .map(cfg -> accessCenter.toEndpoint(cfg).toModel());
    }

    @Override
    public Optional<String> memoryModelId() {
        return accessCenter.memory().map(ModelConfig::id);
    }

    /**
     * 记忆管理端点模型（P1-5 fix）：走专用通道解析 role=memory 端点，
     * 不受 {@link #configOf} 中「memory 角色从对话模型选择中过滤」的影响；
     * 未配置或不可达时回退主力/备用端点。
     */
    @Override
    public ChatModel resolveMemory() {
        ModelConfig memory = accessCenter.memory().orElse(null);
        if (memory == null) {
            return resolve();
        }
        ModelEndpoint endpoint = accessCenter.toEndpoint(memory);
        Optional<ModelConfig> fallback = accessCenter.fallback();
        if (!endpoint.healthy() && fallback.isPresent()) {
            log.warn("记忆端点不可达，回退备用端点：{}", fallback.get().name());
            return accessCenter.toEndpoint(fallback.get()).toModel();
        }
        return endpoint.toModel();
    }

    /** 记忆端点模型名（未配置回退主端点）。 */
    @Override
    public String memoryModelName() {
        ModelConfig memory = accessCenter.memory().orElse(null);
        return memory == null ? modelName() : memory.modelName();
    }

    @Override
    public boolean primaryHealthy() {
        ModelConfig primary = accessCenter.primary();
        if (primary == null) {
            return false;
        }
        return accessCenter.toEndpoint(primary).healthy();
    }

    @Override
    public String modelName() {
        ModelConfig primary = accessCenter.primary();
        return primary == null ? "unknown" : primary.modelName();
    }

    @Override
    public int contextWindow() {
        ModelConfig primary = accessCenter.primary();
        return primary == null ? 0 : primary.contextWindow();
    }

    @Override
    public ModelRouterStatus status() {
        ModelConfig primary = accessCenter.primary();
        ModelConfig fallback = accessCenter.fallback().orElse(null);
        boolean healthy = primary == null ? false : accessCenter.toEndpoint(primary).healthy();
        return new ModelRouterStatus(
                primary == null ? null : primary.id(),
                primary == null ? null : primary.name(),
                primary == null ? null : primary.modelName(),
                fallback == null ? null : fallback.id(),
                fallback == null ? null : fallback.name(),
                healthy,
                primary == null ? 0 : primary.contextWindow());
    }
}
