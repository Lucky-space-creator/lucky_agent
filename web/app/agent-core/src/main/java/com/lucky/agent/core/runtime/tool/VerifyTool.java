package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.verify.VerificationOutcome;
import com.lucky.agent.core.runtime.verify.VerificationRequest;
import com.lucky.agent.core.runtime.verify.Verifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「客观验证」工具（工具化示例）：把原本内联在主循环的验证调用，暴露为 LLM 可调用工具。
 * <p>运行时也可直接调用验证器；本工具让模型在推理中主动请求校验（如「跑一下测试确认」）。</p>
 */
@Component
public class VerifyTool implements RuntimeTool {

    private final Verifier verifier;

    public VerifyTool(Verifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "对目标达成度做校验（客观优先：测试/lint/命令退出码），返回 done 与置信度。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"goal\":{\"type\":\"string\"},"
                + "\"output\":{\"type\":\"string\"},"
                + "\"checkCommands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"
                + "},\"required\":[\"output\"]}";
    }

    @Override
    public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
        String goal = call.arg("goal") == null ? ctx.goal() : call.arg("goal");
        String output = call.arg("output");
        List<String> commands = extractCommands(call);
        VerificationOutcome outcome = verifier.verify(
                new VerificationRequest(goal, output, commands, java.util.Map.of(), ctx, null));
        return ExecutionResult
                .success("done=" + outcome.done() + ", confidence=" + outcome.confidence()
                        + ", summary=" + outcome.summary())
                .withMeta("done", outcome.done())
                .withMeta("confidence", outcome.confidence())
                .withMeta("objective", outcome.objective());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCommands(ToolCall call) {
        Object raw = call.arguments().get("checkCommands");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
