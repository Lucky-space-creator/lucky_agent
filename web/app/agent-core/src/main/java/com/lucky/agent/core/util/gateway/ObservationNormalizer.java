package com.lucky.agent.core.util.gateway;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.ToolResult;

/**
 * Observation 归一化：把工具结果转为可注入记忆/上下文的观察文本。
 */
public class ObservationNormalizer {

    /**
     * 归一化观察结果。
     *
     * @param result 工具结果
     * @return 归一化文本
     */
    public String normalize(Object result) {
        if (result instanceof ToolResult toolResult) {
            return toolResult.ok()
                    ? String.valueOf(toolResult.data())
                    : "错误：" + toolResult.error();
        }
        if (result instanceof ExecResult execResult) {
            if (!execResult.ok()) {
                return "错误：" + execResult.error();
            }
            return execResult.summary() != null ? execResult.summary()
                    : (execResult.content() != null ? execResult.content() : "ok");
        }
        return result == null ? "ok" : String.valueOf(result);
    }
}
