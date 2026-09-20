package com.lucky.agent.memory.support.pipeline;

/**
 * 遗忘/衰减机制：低置信事实周期性降权，长期不印证则遗忘。
 */
public class DecayManager {

    private final double decayPerDay;

    public DecayManager(double decayPerDay) {
        this.decayPerDay = decayPerDay;
    }

    /**
     * 按存续天数施加衰减。
     *
     * @param confidence 原置信度
     * @param ageDays    存续天数
     * @return 衰减后置信度
     */
    public double apply(double confidence, long ageDays) {
        if (ageDays <= 0) {
            return confidence;
        }
        return confidence * Math.pow(decayPerDay, ageDays);
    }

    /**
     * 是否应遗忘（置信度低于阈值）。
     *
     * @param confidence     当前置信度
     * @param forgetThreshold 遗忘阈值
     * @return true 应遗忘
     */
    public boolean shouldForget(double confidence, double forgetThreshold) {
        return confidence < forgetThreshold;
    }
}
