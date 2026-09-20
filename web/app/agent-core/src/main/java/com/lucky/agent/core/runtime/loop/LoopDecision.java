package com.lucky.agent.core.runtime.loop;

/**
 * 主循环决策：由 {@link LoopController} 产出，主循环据此决定「结束还是续跑」。
 *
 * <p>把「是否结束、以什么状态结束、下一轮目标是什么」收敛为一个不可变值，
 * 使主循环内不再出现验证/记忆/守卫/安全阀的分支判断（重构目标 1）。</p>
 *
 * @param terminate 是否终止循环
 * @param status    终止状态（success / stuck / ask / …）
 * @param text      终止时的输出文本
 * @param nextGoal  续跑时的下一轮目标
 */
public record LoopDecision(boolean terminate, String status, String text, String nextGoal) {

    /** 终止循环。 */
    public static LoopDecision finish(String status, String text) {
        return new LoopDecision(true, status, text, null);
    }

    /** 继续下一轮。 */
    public static LoopDecision next(String nextGoal) {
        return new LoopDecision(false, "continue", null, nextGoal);
    }

    /** 是否终止于「需用户确认」语义。 */
    public boolean isAsk() {
        return "ask".equals(status);
    }
}
