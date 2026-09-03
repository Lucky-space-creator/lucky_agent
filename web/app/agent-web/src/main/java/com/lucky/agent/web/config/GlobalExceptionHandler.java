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

    /** 请求体/参数解析失败（WebFlux 输入异常）。*/
    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleServerWebInput(ServerWebInputException e) {
        log.warn("请求输入异常：{}", e.getMessage());
        return Map.of("code", "BAD_REQUEST",
                "error", e.getReason() == null ? "请求参数不合法" : e.getReason());
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
