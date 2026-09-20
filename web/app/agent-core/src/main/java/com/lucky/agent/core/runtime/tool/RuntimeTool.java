package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;

/**
 * 运行时工具（LLM 可调用）：把「拆解步骤 / 启动子代理 / 客观验证 / 达成度判定 / 记忆压缩沉淀」
 * 等原本硬编码在主循环里的能力，统一收敛为工具，由 LLM 决策调用。
 *
 * <p>实现类以 Spring Bean 方式注册，{@link ToolRegistry} 自动收集。</p>
 *
 * <p><b>权限不内联</b>：工具只<b>声明</b>自身所需的最低权限级别（{@link #requiredPermission()}），
 * 是否放行由 {@code PermissionMiddleware} 统一裁决，工具实现本身不做权限判断——
 * 这样新增工具无需重复实现权限逻辑，也不会绕过 deny 规则。</p>
 */
public interface RuntimeTool {

    /** 工具名（LLM function name，唯一）。 */
    String name();

    /** 工具描述（给 LLM 看）。 */
    String description();

    /** 参数 JSON Schema（给 LLM 看，默认无参对象）。 */
    default String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    /**
     * 调用本工具所需的最低权限级别（默认只读）。
     * <p>由权限中间件与当前工作区权限级别比较；不足时转 ASK 而非静默拒绝。</p>
     */
    default PermissionLevel requiredPermission() {
        return PermissionLevel.READ_ONLY;
    }

    /**
     * 失败是否可安全重试（默认 true）。
     * <p>有副作用的工具（写文件、执行命令）若无法保证幂等，应返回 false，
     * 避免重试中间件造成重复副作用。</p>
     */
    default boolean retryable() {
        return true;
    }

    /** 执行工具。 */
    ExecutionResult invoke(ToolCall call, RuntimeContext ctx);
}
