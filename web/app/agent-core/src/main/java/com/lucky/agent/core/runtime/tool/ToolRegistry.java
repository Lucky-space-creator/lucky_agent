package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：收敛全部 {@link RuntimeTool}，为模型生成工具清单，并按名分发调用。
 * <p>构造时可注入 Spring 收集到的 {@code List<RuntimeTool>}，实现 SPI 式插件发现。</p>
 */
public class ToolRegistry {

    private final Map<String, RuntimeTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(Collection<RuntimeTool> initial) {
        if (initial != null) {
            initial.forEach(this::register);
        }
    }

    public final void register(RuntimeTool tool) {
        if (tool != null && tool.name() != null) {
            tools.put(tool.name(), tool);
        }
    }

    public Optional<RuntimeTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<RuntimeTool> all() {
        return tools.values();
    }

    /** 生成给 LLM 的工具清单（name/description/parameters）。 */
    public List<Map<String, Object>> specs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (RuntimeTool tool : tools.values()) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", tool.name());
            spec.put("description", tool.description());
            spec.put("parameters", tool.parametersSchema());
            specs.add(spec);
        }
        return specs;
    }

    /** 分发一次工具调用。 */
    public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
        RuntimeTool tool = tools.get(call.name());
        if (tool == null) {
            return ExecutionResult.failure("未注册的工具: " + call.name());
        }
        return tool.invoke(call, ctx);
    }
}
