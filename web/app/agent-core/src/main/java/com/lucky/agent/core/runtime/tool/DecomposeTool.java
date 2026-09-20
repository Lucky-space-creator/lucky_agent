package com.lucky.agent.core.runtime.tool;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.contract.ToolCall;
import com.lucky.agent.core.runtime.plan.StepDecomposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 「拆解步骤」工具：把原主循环里硬编码的「拆成步骤？」判断暴露为 LLM 可调用工具。
 *
 * <p>拆解结果写入运行时属性（键 {@link #ATTR_STEPS}）而非直接改写控制流，
 * 因此主循环仍只有三步；是否需要按步骤执行由策略插件/运行时决定。</p>
 */
public class DecomposeTool implements RuntimeTool {

    private static final Logger log = LoggerFactory.getLogger(DecomposeTool.class);

    /** 步骤列表属性键（{@code List<StepDecomposer.Step>}）。 */
    public static final String ATTR_STEPS = "steps";

    private final StepDecomposer decomposer;

    public DecomposeTool(StepDecomposer decomposer) {
        this.decomposer = decomposer;
    }

    @Override
    public String name() {
        return "decompose";
    }

    @Override
    public String description() {
        return "把目标拆解为有序步骤（用于多步骤/复杂任务）。返回步骤清单，供后续逐步执行与验证。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"goal\":{\"type\":\"string\",\"description\":\"待拆解的目标，缺省用当前会话目标\"}"
                + "},\"required\":[]}";
    }

    @Override
    public ExecutionResult invoke(ToolCall call, RuntimeContext ctx) {
        String goal = call.arg("goal") == null ? ctx.goal() : call.arg("goal");
        List<StepDecomposer.Step> steps = decomposer.decompose(goal, ctx);
        ctx.attribute(ATTR_STEPS, steps);

        String output = steps.stream()
                .map(s -> s.index() + ". " + s.title())
                .collect(Collectors.joining("\n"));
        log.debug("[decompose] 目标拆解为 {} 步", steps.size());

        return ExecutionResult.success(output)
                .withMeta("stepCount", steps.size())
                .withMeta("steps", steps.stream().map(StepDecomposer.Step::title).toList());
    }

    /** 读取已拆解的步骤（无则返回空列表）。 */
    @SuppressWarnings("unchecked")
    public static List<StepDecomposer.Step> steps(RuntimeContext ctx) {
        Object value = ctx.attribute(ATTR_STEPS);
        return (value instanceof List<?> list) ? (List<StepDecomposer.Step>) list : List.of();
    }
}
