package com.lucky.agent.workflow.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认工具适配器：回显工具名与参数，保证工作流在无外部工具时仍可端到端运行。
 */
public class NoopToolAdapter implements ToolAdapter {

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", toolName);
        result.put("args", args == null ? Map.of() : args);
        result.put("ok", true);
        return result;
    }
}
