package com.lucky.agent.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON-RPC 2.0 响应对象（id/result/error 三者至多其二）。
 *
 * <p>{@link #result} 与 {@link #error} 以懒解析的 {@code Map} 形式承载，
 * 消费方通过 {@link #resultMap()} / {@link #errorMessage()} 访问，避免对
 * 嵌套结构强类型建模。</p>
 *
 * @param id     请求 id（null 表示通知）
 * @param result 成功结果（可为 null）
 * @param error  错误对象（成功时为 null）
 */
public record JsonRpcResponse(Object id, Map<String, Object> result, JsonRpcError error) {

    /** 是否错误响应。 */
    public boolean isError() {
        return error != null;
    }

    /** 错误描述（非错误响应时返回 null）。 */
    public String errorMessage() {
        return error == null ? null : error.message();
    }

    /** 成功结果（可能为 null）。 */
    public Map<String, Object> resultMap() {
        return result;
    }

    /** 解析 JSON 响应文本。 */
    public static JsonRpcResponse parse(ObjectMapper mapper, String json) {
        try {
            var node = mapper.readTree(json);
            Object id = node.hasNonNull("id") ? mapper.convertValue(node.get("id"), Object.class) : null;
            JsonRpcError err = node.has("error") && !node.get("error").isNull()
                    ? mapper.convertValue(node.get("error"), JsonRpcError.class)
                    : null;
            Map<String, Object> result = node.hasNonNull("result")
                    ? mapper.convertValue(node.get("result"), new TypeReference<Map<String, Object>>() {
                    })
                    : null;
            return new JsonRpcResponse(id, result, err);
        } catch (JsonProcessingException e) {
            return new JsonRpcResponse(null, null,
                    new JsonRpcError(JsonRpcError.INTERNAL, "响应解析失败：" + e.getOriginalMessage(), null));
        }
    }

    /** 本地构造一个错误响应（超时/进程退出等）。 */
    public static JsonRpcResponse fail(String message) {
        return new JsonRpcResponse(null, null, new JsonRpcError(JsonRpcError.INTERNAL, message, null));
    }
}
