package com.lucky.agent.core.engine;

import com.lucky.agent.common.constant.Phase;

/**
 * 步数上限守卫 + 早停（防无限试错，降本）。
 *
 * <p>对单 phase 设步数上限（默认 PLAN=12、ACT=30、ASK 无上限直至用户回复）；
 * 超限强制转 ASK 或触发重规划。</p>
 */
public class StepLimitGuard {

    private final int planMaxSteps;
    private final int actMaxSteps;

    public StepLimitGuard(int planMaxSteps, int actMaxSteps) {
        this.planMaxSteps = planMaxSteps;
        this.actMaxSteps = actMaxSteps;
    }

    /**
     * 阶段步数上限。
     *
     * @param phase 阶段
     * @return 上限；ASK 返回 Integer.MAX_VALUE（等用户回复）
     */
    public int maxSteps(Phase phase) {
        return switch (phase) {
            case PLAN -> planMaxSteps;
            case ACT -> actMaxSteps;
            case ASK -> Integer.MAX_VALUE;
        };
    }

    /**
     * 是否已达步数上限（触发早停）。
     *
     * @param phase  阶段
     * @param used   已用步数
     * @return true 超限
     */
    public boolean exceeded(Phase phase, int used) {
        return used >= maxSteps(phase);
    }
}
