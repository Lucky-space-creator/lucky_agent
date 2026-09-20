package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「记忆管理」工具（工具化示例）：把上下文压缩 / 长期记忆沉淀暴露为 LLM 可调用工具。
 * <p>策略约束由工具内部把关：{@code action=record}（沉淀）仅在成功语义下建议调用；
 * {@code action=compact}（压缩）保留 goal/attempts/openQuestions 等结构化字段。</p>
 */
@Component
public class MemoryTool implements RuntimeTool {

    private final MemoryPort memory;

    public MemoryTool(MemoryPort memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "记忆管理：action=compact 压缩上下文；action=record 沉淀长期记忆（仅成功路径）。";
    }

    /** 沉淀会写入记忆文件，需要「修改文件」及以上权限。 */
    @Override
    public com.lucky.agent.common.constant.PermissionLevel requiredPermission() {
        return com.lucky.agent.common.constant.PermissionLevel.MODIFY;
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"action\":{\"type\":\"string\",\"enum\":[\"compact\",\"record\"]},"
                + "\"content\":{\"type\":\"string\"},"
                + "\"openQuestions\":{\"type\":\"string\"}"
                + "},\"required\":[\"action\"]}";
    }

    @Override
    public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
        String action = call.arg("action");
        if ("compact".equalsIgnoreCase(action)) {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put(MemoryPort.Fields.GOAL, ctx.goal());
            structured.put(MemoryPort.Fields.OPEN_QUESTIONS,
                    call.arg("openQuestions") == null ? "" : call.arg("openQuestions"));
            memory.compact(ctx.ref(), structured);
            return ExecutionResult.success("已压缩上下文（保留结构化字段）");
        }
        if ("record".equalsIgnoreCase(action)) {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put(MemoryPort.Fields.GOAL, ctx.goal());
            memory.recordLongTerm(ctx.sessionId(), call.arg("content"), structured);
            return ExecutionResult.success("已沉淀长期记忆");
        }
        return ExecutionResult.failure("未知 action: " + action);
    }
}
