package com.lucky.agent.web.config;

import com.lucky.agent.common.exception.AgentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理：统一错误 JSON {@code {code, error}}，避免堆栈外泄。
 */

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    
    /** 框架业务异常。*/
    @ExceptionHandler(AgentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleAgentException(AgentException e) {
        log.warn("业务异常：code={} msg={}", e.getCode(), e.getMessage());
        return Map.of("code", e.getCode() == null ? "AGENT_ERROR" : e.getCode(), "error", e.getMessage());
    }

    /** 参数/业务非法。*/
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常：{}", e.getMessage());
        return Map.of("code", "BAD_REQUEST", "error", e.getMessage());
    }

    /**
     * 请求体/参数解析失败（WebFlux 输入异常）。
     *
     * <p><b>为何不直接把 {@code e.getReason()} 回传前端：</b>Jackson 抛出的原始 reason 是
     * {@code "Failed to read HTTP message"} 这类英文框架内部措辞，既不含出错字段、也无法指导用户，
     * 还会让前端 toast 显示一串「报错黑话」。这里统一改写为可读中文，把真正的细节留在服务端日志。</p>
     */
    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleServerWebInput(ServerWebInputException e) {
        String reason = e.getReason();
        log.warn("请求输入异常：{} | reason={}", e.getMessage(), reason);
        return Map.of("code", "BAD_REQUEST", "error", readableInputMessage(reason));
    }

    /** 把框架原始输入异常 reason 翻译为用户可读的中文说明。 */
    private String readableInputMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "请求参数不合法";
        }
        String lower = reason.toLowerCase();
        // Jackson 反序列化失败：最常见的就是「枚举字面量大小写不符」与「字段类型不匹配」
        if (lower.contains("failed to read http message")) {
            return "请求体解析失败：字段类型或枚举值不合法（请对照接口契约检查请求参数）";
        }
        if (lower.contains("no enum constant") || lower.contains("enum")) {
            return "请求体解析失败：枚举字段取值不在允许范围内";
        }
        // 其余情况给出通用前缀，避免把框架内部措辞原样暴露
        return "请求参数不合法：" + reason;
    }

    /** 路由/资源未命中（状态码透传）。*/
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        log.warn("HTTP 状态异常：status={} reason={}", e.getStatusCode().value(), e.getReason());
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("code", "HTTP_" + e.getStatusCode().value(),
                        "error", e.getReason() == null ? "请求处理失败" : e.getReason()));
    }

    /** 兜底。*/
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleGeneric(Exception e) {
        log.error("未捕获异常", e);
        return Map.of("code", "INTERNAL_ERROR", "error", "服务内部错误，请查看日志");
    }
}
