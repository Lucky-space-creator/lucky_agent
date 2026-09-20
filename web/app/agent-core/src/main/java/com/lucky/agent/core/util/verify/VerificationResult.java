package com.lucky.agent.core.util.verify;

/**
 * 客观验证结果（流程图 D / I 节点）。
 *
 * <p>验证器分两类信号：</p>
 * <ul>
 *   <li><b>客观信号</b>（{@code objective=true}）：文件存在性、命令退出码、HTTP 状态码、测试用例结果等
 *       不依赖模型主观判断的证据，判定结果可复现；</li>
 *   <li><b>主观信号</b>（{@code objective=false}）：LLM 依据执行日志做的达成度判定，作为客观信号缺失时的兜底。</li>
 * </ul>
 *
 * <p>聚合策略遵循「客观优先」：任一客观验证器判失败即判定未达成，客观信号全部通过才回到主观判定。</p>
 *
 * @param verifier  验证器标识（file / command / llm）
 * @param passed    是否通过
 * @param detail    证据摘要（供前端思考区展示、供 LLM 判定注入）
 * @param objective 是否为客观信号
 * @param skipped   是否因权限/配置等原因跳过（跳过不计入通过也不计入失败）
 */
public record VerificationResult(String verifier,
                                 boolean passed,
                                 String detail,
                                 boolean objective,
                                 boolean skipped) {

    /** 通过（客观信号）。 */
    public static VerificationResult passed(String verifier, String detail) {
        return new VerificationResult(verifier, true, detail, true, false);
    }

    /** 未通过（客观信号）。 */
    public static VerificationResult failed(String verifier, String detail) {
        return new VerificationResult(verifier, false, detail, true, false);
    }

    /** 跳过（不计入判定）。 */
    public static VerificationResult skipped(String verifier, String detail) {
        return new VerificationResult(verifier, true, detail, true, true);
    }

    /** 主观判定结果（LLM 依据证据给出的达成度结论）。 */
    public static VerificationResult judged(boolean passed, String detail) {
        return new VerificationResult("llm", passed, detail, false, false);
    }
}
