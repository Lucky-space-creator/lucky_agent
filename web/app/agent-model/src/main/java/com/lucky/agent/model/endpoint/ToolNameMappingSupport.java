package com.lucky.agent.model.endpoint;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * 官方模型接入下的工具名映射支持：请求前把点分工具名清洗为厂商安全名（{@code ^[a-zA-Z0-9_-]+$}），
 * 响应后把模型回传的工具调用名还原为内部点分名，保证编排层（{@code ReactEngine} 按 {@code skill.}/
 * {@code mcp.} 前缀分发、{@code ToolGateway} 按名分发）不受影响。
 *
 * <p>仅当请求确实携带工具时做映射，否则原样透传（零开销、零风险）。</p>
 */
final class ToolNameMappingSupport {

    private ToolNameMappingSupport() {
    }

    /**
     * 清洗请求中的工具名并重建请求；无工具时原样返回。
     *
     * @param request 原始请求
     * @param names   工具名映射器（调用方维护，用于响应还原）
     * @return 工具名已清洗的请求
     */
    static ChatRequest sanitizeRequest(ChatRequest request, ToolNameMapper names) {
        List<ToolSpecification> tools = request.toolSpecifications();
        if (tools == null || tools.isEmpty()) {
            return request;
        }
        boolean[] changed = {false};
        List<ToolSpecification> mapped = tools.stream().map(spec -> {
            String wireName = names.register(spec.name());
            if (wireName.equals(spec.name())) {
                return spec;
            }
            changed[0] = true;
            return spec.toBuilder().name(wireName).build();
        }).toList();
        return changed[0] ? request.toBuilder().toolSpecifications(mapped).build() : request;
    }

    /**
     * 还原响应中工具调用的名字；无工具调用时原样返回。
     *
     * @param response 原始响应
     * @param names    工具名映射器（与清洗请求时同一实例）
     * @return 工具名已还原的响应
     */
    static ChatResponse restoreResponse(ChatResponse response, ToolNameMapper names) {
        AiMessage ai = response.aiMessage();
        if (ai == null || ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
            return response;
        }
        List<ToolExecutionRequest> mapped = ai.toolExecutionRequests().stream().map(req ->
                req.toBuilder().name(names.internal(req.name())).build()).toList();
        AiMessage restored = ai.toBuilder().toolExecutionRequests(mapped).build();
        return response.toBuilder().aiMessage(restored).build();
    }
}
