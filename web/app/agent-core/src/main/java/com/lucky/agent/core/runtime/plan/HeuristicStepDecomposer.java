package com.lucky.agent.core.runtime.plan;

import com.lucky.agent.core.runtime.contract.RuntimeContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启发式步骤拆解器：不依赖模型，纯结构化切分，保证拆解能力在无模型时也可用。
 *
 * <p>识别优先级：</p>
 * <ol>
 *   <li>目标中的<b>编号/项目符号行</b>（{@code 1. } / {@code 1、} / {@code - } / {@code * }）；</li>
 *   <li>目标中的<b>序列连接词</b>（然后 / 接着 / 再 / 并且 / ；/ ;）切分；</li>
 *   <li>兜底三段式：分析现状与约束 → 实施变更 → 验证结果。</li>
 * </ol>
 *
 * <p>上限 {@value #MAX_STEPS} 步，避免拆解粒度失控导致轮次爆炸（对应已知缺口「递归列举失控」的硬约束思路）。</p>
 */
public class HeuristicStepDecomposer implements StepDecomposer {

    private static final int MAX_STEPS = 8;

    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:\\d+[.、)]|[-*•])\\s*(.+)$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:然后|接着|之后再|再然后|并且|；|;)\\s*");

    @Override
    public List<Step> decompose(String goal, RuntimeContext ctx) {
        List<String> parts = fromListLines(goal);
        if (parts.size() < 2) {
            parts = fromSeparators(goal);
        }
        if (parts.size() < 2) {
            return defaultPlan();
        }
        List<Step> steps = new ArrayList<>();
        int index = 1;
        for (String part : parts) {
            if (index > MAX_STEPS) {
                break;
            }
            String title = part.trim();
            if (title.isEmpty()) {
                continue;
            }
            steps.add(Step.of(index, title.length() > 120 ? title.substring(0, 120) + "…" : title));
            index++;
        }
        return steps.size() >= 2 ? steps : defaultPlan();
    }

    private List<String> fromListLines(String goal) {
        List<String> parts = new ArrayList<>();
        if (goal == null) {
            return parts;
        }
        for (String line : goal.split("\\R")) {
            Matcher matcher = LIST_LINE.matcher(line);
            if (matcher.matches()) {
                parts.add(matcher.group(1));
            }
        }
        return parts;
    }

    private List<String> fromSeparators(String goal) {
        if (goal == null) {
            return List.of();
        }
        return Arrays.stream(SEPARATOR.split(goal))
                .map(String::trim)
                .filter(s -> s.length() >= 2)
                .toList();
    }

    private List<Step> defaultPlan() {
        return List.of(
                Step.of(1, "分析与现状确认（约束、涉及文件、已知信息）"),
                Step.of(2, "实施变更"),
                Step.of(3, "客观验证结果（测试/命令退出码）"));
    }
}
