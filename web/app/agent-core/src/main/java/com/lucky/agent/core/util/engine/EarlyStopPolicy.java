package com.lucky.agent.core.util.engine;

/**
 * 早停策略：低置信早停转 ASK。
 */
public class EarlyStopPolicy {

    private final double confidenceThreshold;

    public EarlyStopPolicy(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 是否应早停（置信度低于阈值）。
     *
     * @param confidence 模型置信度（0~1），无法估计时视为高置信
     * @return true 早停转 ASK
     */
    public boolean shouldStop(Double confidence) {
        return confidence != null && confidence < confidenceThreshold;
    }
}
