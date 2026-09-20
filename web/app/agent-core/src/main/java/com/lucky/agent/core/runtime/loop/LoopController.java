package com.lucky.agent.core.runtime.loop;

import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import com.lucky.agent.core.runtime.verify.VerificationOutcome;
import com.lucky.agent.core.runtime.verify.VerificationRequest;
import com.lucky.agent.core.runtime.verify.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 循环控制器（厚运行时的一部分）：把原主循环里内联的「达成度判定 / 卡死守卫 / 记忆沉淀与压缩 /
 * 早停转 ASK」全部收敛到一处，使主循环只剩三步。
 *
 * <p>职责边界：只做<b>决策</b>，不做执行——执行由工具与运行时承担。</p>
 *
 * <p>记忆策略（重构目标 7）：</p>
 * <ul>
 *   <li>达成 → {@link MemoryPort#recordLongTerm} 沉淀长期记忆；</li>
 *   <li>未达成 → 仅 {@link MemoryPort#compact} 压缩上下文（保留结构化字段），不产生长期记忆。</li>
 * </ul>
 */
public class LoopController {

    private static final Logger log = LoggerFactory.getLogger(LoopController.class);

    /** 上一轮判定摘要（用于卡死守卫）。 */
    public static final String ATTR_LAST_VERDICT = "lastVerdictSummary";

    private final MemoryPort memory;
    private final int maxIterations;
    private final double askConfidenceThreshold;

    public LoopController(MemoryPort memory, int maxIterations, double askConfidenceThreshold) {
        this.memory = memory;
        this.maxIterations = Math.max(1, maxIterations);
        this.askConfidenceThreshold = askConfidenceThreshold;
    }

    public int maxIterations() {
        return maxIterations;
    }

    /**
     * 对一轮结果做决策。
     *
     * @param iteration 当前轮次（1 起）
     * @param goal      本轮目标
     * @param output    本轮输出
     * @param rc        运行时上下文
     * @param runtime   运行时（用于按策略选择验证器）
     * @return 终止或续跑决策
     */
    public LoopDecision decide(int iteration, String goal, String output, RuntimeContext rc, AgentRuntime runtime) {
        return decide(iteration, goal, output, rc, runtime, null);
    }

    /**
     * 计划感知的决策重载。
     *
     * @param plan 本轮计划（可为 null）。非空时客观验证按计划内的 {@code verify} 声明逐步骤执行，
     *             这是 langgraph 主循环复用本控制器时保持其原有验证语义的关键
     */
    public LoopDecision decide(int iteration, String goal, String output, RuntimeContext rc,
                              AgentRuntime runtime, Plan plan) {
        VerificationOutcome outcome = verify(goal, output, rc, runtime, plan);

        if (outcome.done()) {
            // 成功才沉淀长期记忆
            memory.recordLongTerm(rc.ref(), output, structured(rc, goal, iteration, outcome));
            return LoopDecision.finish("success", blankTo(output, outcome.summary()));
        }

        // 卡死守卫：连续两轮结论一致即提前终止，避免空转烧 token
        String normalized = normalize(outcome.summary());
        Object previous = rc.attribute(ATTR_LAST_VERDICT);
        if (iteration > 1 && !normalized.isEmpty() && normalized.equals(previous)) {
            log.info("[loop] 判定连续两轮一致，提前终止（stuck）: {}", outcome.summary());
            memory.compact(rc.ref(), structured(rc, goal, iteration, outcome));
            return LoopDecision.finish("stuck", blankTo(outcome.summary(), output));
        }
        rc.attribute(ATTR_LAST_VERDICT, normalized);

        memory.compact(rc.ref(), structured(rc, goal, iteration, outcome));

        // 低置信早停：置信度低于阈值（客观通过率过低，或仅有极弱主观判定）→ 转 ASK 让用户决策。
        // 条件只看向置信度，不再区分主客观——这正是 core.earlyStopConfidenceThreshold 的原意，
        // 也让两个主循环共用同一份早停语义。
        if (outcome.confidence() < askConfidenceThreshold) {
            log.info("[loop] 判定置信度 {} 低于阈值 {}，转 ASK", outcome.confidence(), askConfidenceThreshold);
            return LoopDecision.finish("ask",
                    "无法确认目标是否达成（置信度 " + outcome.confidence() + "），需你确认：" + blankTo(outcome.summary(), output));
        }

        String nextGoal = (outcome.continueGoal() == null || outcome.continueGoal().isBlank())
                ? goal : outcome.continueGoal();
        return LoopDecision.next(nextGoal);
    }

    private VerificationOutcome verify(String goal, String output, RuntimeContext rc, AgentRuntime runtime,
                                      Plan plan) {
        Verifier verifier = runtime.verifierFor(rc);
        if (verifier == null) {
            return VerificationOutcome.subjective(false, "未配置验证器", 0.2);
        }
        VerificationOutcome outcome = verifier.verify(
                new VerificationRequest(goal, output, java.util.List.of(), Map.of(), rc, plan));
        return outcome == null
                ? VerificationOutcome.subjective(false, "验证器未表态", 0.2)
                : outcome;
    }

    /** 压缩/沉淀共用的结构化字段（对应重构目标 7 的保留字段）。 */
    private Map<String, Object> structured(RuntimeContext rc, String goal, int iteration,
                                           VerificationOutcome outcome) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(MemoryPort.Fields.GOAL, nullSafe(goal));
        fields.put(MemoryPort.Fields.ATTEMPTS, iteration);
        fields.put(MemoryPort.Fields.CONSTRAINTS, nullSafe(str(rc.attribute("constraints"))));
        fields.put(MemoryPort.Fields.OPEN_QUESTIONS, nullSafe(outcome.continueGoal()));
        fields.put(MemoryPort.Fields.TOOL_USAGE, rc.attribute("toolUsage") == null ? "" : rc.attribute("toolUsage"));
        return fields;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\p{P}\\p{Z}\\p{C}]+", " ").trim();
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? (fallback == null ? "" : fallback) : value;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
