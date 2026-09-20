package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中间件上下文：在中间件链各钩子间传递的可变载体。
 * <p>中间件可通过 {@link #block(String)} 短路后续链（如预算耗尽、权限拒绝）。</p>
 */
public class MiddlewareContext {

    private final RuntimeContext runtime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private ToolCall toolCall;
    private ExecutionResult result;
    private boolean blocked;
    private String blockReason;

    public MiddlewareContext(RuntimeContext runtime) {
        this.runtime = runtime;
    }

    public RuntimeContext runtime() {
        return runtime;
    }

    public ToolCall toolCall() {
        return toolCall;
    }

    public void toolCall(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    public ExecutionResult result() {
        return result;
    }

    public void result(ExecutionResult result) {
        this.result = result;
    }

    public boolean blocked() {
        return blocked;
    }

    public String blockReason() {
        return blockReason;
    }

    /** 短路中间件链。 */
    public void block(String reason) {
        this.blocked = true;
        this.blockReason = reason;
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    public void attribute(String key, Object value) {
        attributes.put(key, value);
    }
}
