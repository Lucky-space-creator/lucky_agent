package com.lucky.agent.common.exception;

/**
 * 框架统一异常。
 *
 * <p>按失败域携带错误码，便于上层（Web/CLI）给出可读提示并记录日志；
 * 所有异常必须记录完整堆栈，不允许静默吞掉异常。</p>
 */
public class AgentException extends RuntimeException {

    /** 错误码（失败域），如 {@code EXEC_OUT_OF_BOUNDS}、{@code PERMISSION_DENIED}。 */
    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
