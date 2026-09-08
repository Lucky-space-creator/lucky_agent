package com.lucky.agent.model.api;

import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.model.api.dto.ModelRouterStatus;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Optional;

/**
 * 模型路由契约。
 *
 * <p>多模型时分层：压缩/匹配用小模型，主推理用大模型；单模型退化为直通。
 * 主端点探活失败自动切备用（fallback）。</p>
 */
@Remote(serviceName = "model-router")
public interface ModelRouter {

    /** 解析主模型（LangChain4j ChatModel），供引擎调用。 */
    ChatModel resolve();

    /**
     * 解析指定端点的模型。
     * {@code modelId} 为空或端点不存在时回退到默认路由，保证老调用方行为不变。
     */
    default ChatModel resolve(String modelId) {
        return resolve();
    }

    /**
     * 解析指定端点的流式模型；端点或模型不支持流式时返回 {@code null}（调用方回退非流式）。
     * {@code modelId} 为空或端点不存在时回退主端点。
     */
    default StreamingChatModel resolveStreaming(String modelId) {
        return null;
    }

    /** 指定端点的模型名；{@code modelId} 为空或不存在时回退主端点。 */
    default String modelName(String modelId) {
        return modelName();
    }

    /** 指定端点的上下文窗口；{@code modelId} 为空或不存在时回退主端点。 */
    default int contextWindow(String modelId) {
        return contextWindow();
    }

    /** 解析备用模型（无备用返回空）。 */
    Optional<ChatModel> resolveFallback();

    /**
     * 记忆管理 Agent 端点 id（role=memory，会话记忆总结专用）。
     * 未配置时返回空，调用方应回退主力模型（{@link #resolve()} / {@code resolve(null)}）。
     */
    default Optional<String> memoryModelId() {
        return Optional.empty();
    }

    /** 主端点是否健康。 */
    boolean primaryHealthy();

    /** 主端点模型名（供事件流/结果记录）。 */
    String modelName();

    /** 主端点上下文窗口大小（供压缩阈值判断）。 */
    int contextWindow();

    /** 路由配置快照（主/备端点、探活状态），供状态面板查询。 */
    ModelRouterStatus status();
}
