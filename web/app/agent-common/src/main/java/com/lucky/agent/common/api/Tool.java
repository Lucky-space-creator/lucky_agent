package com.lucky.agent.common.api;

import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 统一工具抽象，所有能力模块（memory/skill/mcp/executor）实现并注册到 core 的 ToolGateway。
 *
 * <p>工具错误以 {@link ToolResult#isError()} 返回，不向上抛异常，由模型读取错误后重试或换路径。</p>
 */
public interface Tool {

    /** 工具唯一名（如 {@code file.read}），用于分发与模型语义。 */
    String name();

    /** 工具描述，供 LLM 语义理解与 Skill 匹配。 */
    String description();

    /** 工具注解（readOnly/destructive/idempotent），决定调度方式。 */
    ToolAnnotations annotations();

    /**
     * 执行工具。
     *
     * @param ctx  会话执行上下文
     * @param args 入参（来自模型工具调用）
     * @return 工具结果，错误以 ok=false 返回
     */
    Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args);
}
