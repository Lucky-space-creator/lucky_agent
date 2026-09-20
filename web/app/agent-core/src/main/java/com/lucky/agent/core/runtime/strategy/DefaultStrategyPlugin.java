package com.lucky.agent.core.runtime.strategy;

import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.plan.StepDecomposer;
import com.lucky.agent.core.runtime.tool.DecomposeTool;
import com.lucky.agent.core.runtime.tool.SpawnSubAgentTool;
import com.lucky.agent.core.runtime.verify.Verifier;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 默认策略插件：以保守的启发式规则决定「何时拆解、何时派子代理」，
 * 替代原主循环中内联的 {@code shouldDecompose} / {@code shouldSpawnSubAgents} 判断。
 *
 * <p>设计原则：<b>宁可少拆，不可乱拆</b>。拆解与子代理都是放大 token 与轮次的昂贵动作，
 * 因此需要多个信号同时成立才触发；一旦已拆解，则不再重复拆解（幂等），
 * 子代理也不再派生孙代理（配合深度限制防止递归失控）。</p>
 *
 * <p>顺序 {@value #ORDER}：作为兜底策略排在所有自定义策略之后（自定义策略用更小的 order 抢占）。</p>
 */
public class DefaultStrategyPlugin implements StrategyPlugin {

    public static final int ORDER = 100;

    private static final Pattern NUMBERED_LINE = Pattern.compile("(?m)^\\s*(?:\\d+[.、)]|[-*•])\\s+\\S");
    private static final Pattern SEQUENCE_WORD = Pattern.compile("然后|接着|并且|同时|分别|再|步骤|首先|其次|最后");
    private static final Pattern PARALLEL_INTENT = Pattern.compile("并行|同时进行|分别|独立|多个|各自|互不依赖");

    private final int minSignals;
    private final int minStepsForSubAgent;
    private final boolean subAgentEnabled;

    public DefaultStrategyPlugin(int minSignals, int minStepsForSubAgent, boolean subAgentEnabled) {
        this.minSignals = Math.max(1, minSignals);
        this.minStepsForSubAgent = Math.max(2, minStepsForSubAgent);
        this.subAgentEnabled = subAgentEnabled;
    }

    @Override
    public String name() {
        return "default";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean shouldDecompose(RuntimeContext ctx, String llmOutput) {
        if (!DecomposeTool.steps(ctx).isEmpty()) {
            return false; // 已拆解：保持幂等，避免每轮重复拆解
        }
        return signals(ctx.goal(), llmOutput) >= minSignals;
    }

    @Override
    public boolean shouldSpawnSubAgents(RuntimeContext ctx, String llmOutput) {
        if (!subAgentEnabled) {
            return false;
        }
        if (currentDepth(ctx) > 0) {
            return false; // 子代理不再派生孙代理：配合深度限制防递归
        }
        List<StepDecomposer.Step> steps = DecomposeTool.steps(ctx);
        if (steps.size() < minStepsForSubAgent) {
            return false; // 未拆解或步骤太少：单 Agent 自跑更省
        }
        String text = (nullSafe(ctx.goal()) + " " + nullSafe(llmOutput));
        return PARALLEL_INTENT.matcher(text).find();
    }

    @Override
    public Verifier verifier(RuntimeContext ctx, Verifier defaultVerifier) {
        return defaultVerifier; // 默认沿用运行时验证链
    }

    /** 统计拆解信号数量：编号行 + 序列词 + 长度。 */
    private int signals(String goal, String llmOutput) {
        String text = nullSafe(goal);
        int count = 0;
        if (NUMBERED_LINE.matcher(text).find()) {
            count += 2; // 已显式编号，强信号
        }
        if (SEQUENCE_WORD.matcher(text).find()) {
            count++;
        }
        if (text.length() >= 60) {
            count++;
        }
        if (NUMBERED_LINE.matcher(nullSafe(llmOutput)).find()) {
            count++;
        }
        return count;
    }

    private int currentDepth(RuntimeContext ctx) {
        Object value = ctx.attribute(SpawnSubAgentTool.ATTR_DEPTH);
        return (value instanceof Number n) ? n.intValue() : 0;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
