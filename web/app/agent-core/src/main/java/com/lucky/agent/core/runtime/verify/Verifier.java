package com.lucky.agent.core.runtime.verify;

/**
 * 验证器：对执行结果做达成度判定。
 * <p>约定「客观优先」：{@link #objective()} 返回 true 的验证器（测试、lint、类型检查、命令退出码等）
 * 结论优先于 LLM 自评；LLM 自评仅作兜底，且必须在 {@link VerificationOutcome#confidence()} 标记较低置信度。</p>
 */
public interface Verifier {

    String name();

    /** 是否客观验证器。 */
    boolean objective();

    VerificationOutcome verify(VerificationRequest request);
}
