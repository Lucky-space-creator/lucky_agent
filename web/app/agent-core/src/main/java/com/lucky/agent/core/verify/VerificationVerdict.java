package com.lucky.agent.core.verify;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证聚合结论（流程图 I → J 节点）。
 *
 * <p>{@code done=true} 表示整体目标已达成，主回环可进入安全阀检查并输出总结；
 * {@code done=false} 时 {@code continueGoal} 携带「需补齐事项」回到 B 节点再分析。</p>
 *
 * @param done         是否已达成用户目标
 * @param summary      达成时的总结文本（未达成时为已完成的进度说明）
 * @param continueGoal 未达成时的下一轮目标（原始目标 + 需补齐事项）
 * @param evidence     本轮全部验证证据（客观 + 主观）
 */
public record VerificationVerdict(boolean done,
                                  String summary,
                                  String continueGoal,
                                  List<VerificationResult> evidence) {

    public VerificationVerdict(boolean done, String summary, String continueGoal,
                               List<VerificationResult> evidence) {
        this.done = done;
        this.summary = summary == null ? "" : summary;
        this.continueGoal = continueGoal == null ? "" : continueGoal;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 是否命中客观信号（存在未被跳过的客观验证结果）。 */
    public boolean hasObjectiveSignal() {
        return evidence.stream().anyMatch(e -> e.objective() && !e.skipped());
    }

    /**
     * 证据文本（注入 LLM 判定提示词，让主观判定建立在客观证据之上）。
     *
     * @return 逐条罗列的证据文本；无证据时返回空串
     */
    public String evidenceText() {
        if (evidence.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (VerificationResult e : evidence) {
            sb.append("\n- [").append(e.verifier()).append("] ")
                    .append(e.skipped() ? "跳过" : (e.passed() ? "通过" : "未通过"))
                    .append("：").append(e.detail());
        }
        return sb.toString();
    }

    /** 客观失败项（作为下一轮需补齐事项的硬依据）。 */
    public List<VerificationResult> objectiveFailures() {
        List<VerificationResult> failures = new ArrayList<>();
        for (VerificationResult e : evidence) {
            if (e.objective() && !e.skipped() && !e.passed()) {
                failures.add(e);
            }
        }
        return failures;
    }
}
