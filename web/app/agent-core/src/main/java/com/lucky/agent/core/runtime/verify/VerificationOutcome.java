package com.lucky.agent.core.runtime.verify;

import java.util.List;

/**
 * 验证结论。
 *
 * @param done         是否达成
 * @param confidence   置信度（0..1；客观验证=1.0，主观判定一般 &lt; 0.6）
 * @param objective    是否来自客观验证（测试/lint/退出码等）
 * @param evidence     证据描述
 * @param summary      结论摘要
 * @param continueGoal 未达成时的续跑目标（供下一轮使用）
 */
public record VerificationOutcome(
        boolean done,
        double confidence,
        boolean objective,
        List<String> evidence,
        String summary,
        String continueGoal) {

    public VerificationOutcome {
        evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
    }

    public static VerificationOutcome done(String summary, List<String> evidence) {
        return new VerificationOutcome(true, 1.0, true, evidence, summary, null);
    }

    public static VerificationOutcome notDone(String summary, String continueGoal, double confidence) {
        return new VerificationOutcome(false, confidence, false, List.of(), summary, continueGoal);
    }

    public static VerificationOutcome subjective(boolean done, String summary, double confidence) {
        return new VerificationOutcome(done, confidence, false, List.of(), summary, null);
    }
}
