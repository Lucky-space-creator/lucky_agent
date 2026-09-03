package com.lucky.agent.core.subagent;

import com.lucky.agent.common.dto.AgentEvent;

import java.util.List;

/**
 * 任务分解（MVP：启发式）。
 *
 * <p>默认单 Agent；仅当任务可分解且收益明确时，主 Agent 拆分子任务（Phase 3 引入 LLM 分解）。
 * 复杂任务被拆成小任务列表，经 {@code task_plan} 事件推送给前端展示。</p>
 */
public class TaskDecomposer {

    /** 显式拆解意图词：命中即走编排器（分析 → 拆分 → 逐任务执行 → 客观验证）。 */
    private static final List<String> DECOMPOSE_INTENT = List.of(
            "计划", "拆解", "步骤", "planner", "subagent", "子代理", "分步", "拆分");

    /** 多目标信号词：命中两个及以上视为复杂任务。 */
    private static final List<String> MULTI_SIGNALS = List.of(
            "并且", "同时", "然后", "接着", "再", "以及", "还有", "另外", "最后", "之后",
            "多个", "分别", "依次", "逐一", "所有", "全部", "重构", "改造", "实现", "并");

    /** 触发编排的最短目标长度（过短的目标不值得拆分）。 */
    private static final int MIN_COMPLEX_LENGTH = 12;

    /**
     * 判断是否复杂任务（走编排器主回环）。
     *
     * <p>判定分两级：命中显式拆解意图词直接判复杂；否则按多目标信号词计数，
     * 命中两个及以上且目标长度达标时判复杂，避免单句长问题被误判成多步任务。</p>
     *
     * @param goal 目标
     * @return true 复杂
     */
    public boolean isComplex(String goal) {
        if (goal == null) {
            return false;
        }
        String g = goal.toLowerCase();
        for (String intent : DECOMPOSE_INTENT) {
            if (g.contains(intent)) {
                return true;
            }
        }
        if (g.length() < MIN_COMPLEX_LENGTH) {
            return false;
        }
        int signals = 0;
        for (String signal : MULTI_SIGNALS) {
            if (g.contains(signal)) {
                signals++;
                if (signals >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 分解任务（MVP：单任务；真实分解在 Phase 3）。
     *
     * @param goal 目标
     * @return 小任务清单
     */
    public List<AgentEvent.TaskItem> decompose(String goal) {
        return List.of(new AgentEvent.TaskItem("t1", goal));
    }
}
