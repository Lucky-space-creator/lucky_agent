package com.lucky.agent.core.runtime.verify;

import java.util.Comparator;
import java.util.List;

/**
 * 组合验证器：按「客观优先」顺序依次求值，取首个表态（非 null）的结论。
 * <p>典型装配：{@code [ExternalCommandVerifier, FileVerifier, LlmJudgeVerifier]}。
 * 客观验证器在前；LLM 自评兜底在后，且其置信度较低。若全部不表态，默认判「未达成（低置信）」。</p>
 */
public class CompositeVerifier implements Verifier {

    private final List<Verifier> verifiers;

    public CompositeVerifier(List<Verifier> verifiers) {
        this.verifiers = (verifiers == null ? List.<Verifier>of() : verifiers).stream()
                // 客观验证器优先，其余保持稳定顺序
                .sorted(Comparator.comparingInt(v -> v.objective() ? 0 : 1))
                .toList();
    }

    @Override
    public String name() {
        return "composite";
    }

    @Override
    public boolean objective() {
        return verifiers.stream().anyMatch(Verifier::objective);
    }

    @Override
    public VerificationOutcome verify(VerificationRequest request) {
        for (Verifier verifier : verifiers) {
            VerificationOutcome outcome = verifier.verify(request);
            if (outcome != null) {
                return outcome;
            }
        }
        return VerificationOutcome.subjective(false, "无可用的客观验证器，默认判未达成", 0.3);
    }
}
