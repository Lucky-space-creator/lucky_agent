package com.lucky.agent.mcp.support.transport;

/**
 * JSON-RPC 2.0 错误对象。
 *
 * @param code    错误码（-32700 至 -32000 保留，服务端自定义为正数）
 * @param message 错误描述
 * @param data    附加数据（可为空）
 */
public record JsonRpcError(int code, String message, Object data) {

    /** 通用内部错误（本地侧拼接错误时使用）。 */
    public static final int INTERNAL = -32000;

    public JsonRpcError {
        if (message == null) {
            message = "";
        }
    }
}
