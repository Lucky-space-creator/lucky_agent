package com.lucky.agent.core.runtime.verify;

import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.runtime.contract.RuntimeContext;

import java.util.List;
import java.util.Map;

/**
 * 验证请求：待验证的目标 / 输出 / 客观校验命令 / 证据 / 运行时上下文 / 计划。
 *
 * <p>{@code runtime} 使验证器可以访问会话上下文、事件发布器与预算，
 * 从而让「客观验证」复用既有 {@code FileService}（受权限与执行臂边界约束）而非自建执行通道。</p>
 *
 * @param goal          目标
 * @param output        待验证的执行输出
 * @param checkCommands 客观校验命令（如 mvn test / npm lint），退出码 0 视为通过
 * @param evidence      已知证据（文件列表、退出码等）
 * @param runtime       运行时上下文（可为 null：轻量验证器不依赖它）
 * @param plan          本轮计划（可选）。携带真实计划时，验证链按其 {@code verify} 声明做逐步骤
 *                      客观校验；为 null 时由 {@link ChainVerifierAdapter} 依据
 *                      {@code checkCommands}/{@code evidence.files} 合成校验计划
 */
public record VerificationRequest(
        String goal,
        String output,
        List<String> checkCommands,
        Map<String, Object> evidence,
        RuntimeContext runtime,
        Plan plan) {

    public VerificationRequest {
        checkCommands = (checkCommands == null) ? List.of() : List.copyOf(checkCommands);
        evidence = (evidence == null) ? Map.of() : Map.copyOf(evidence);
    }

    public static VerificationRequest of(String goal, String output) {
        return new VerificationRequest(goal, output, List.of(), Map.of(), null, null);
    }

    public static VerificationRequest of(String goal, String output, RuntimeContext runtime) {
        return new VerificationRequest(goal, output, List.of(), Map.of(), runtime, null);
    }

    /** 携带真实计划的请求：保留计划内声明的逐步骤客观校验。 */
    public static VerificationRequest of(String goal, String output, RuntimeContext runtime, Plan plan) {
        return new VerificationRequest(goal, output, List.of(), Map.of(), runtime, plan);
    }
}
