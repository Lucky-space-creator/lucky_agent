package com.lucky.agent.core.runtime.budget;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分层预算管理器：全局 / 任务 / 步骤 / 子代理四级预算。
 * <p>token 用量自底向上累计（步骤 → 任务 → 全局），任一级耗尽即触发熔断；每级有独立超时与重试上限。
 * 对应「薄主循环 + 厚运行时」中下沉到运行时的预算关注点。</p>
 */
public class BudgetManager {

    private final BudgetScope global;
    private final Map<String, BudgetScope> tasks = new ConcurrentHashMap<>();
    private final Map<String, BudgetScope> steps = new ConcurrentHashMap<>();
    private final Map<String, BudgetScope> subAgents = new ConcurrentHashMap<>();

    public BudgetManager(BudgetScope global) {
        this.global = global;
    }

    public BudgetScope global() {
        return global;
    }

    public BudgetScope task(String taskId, long maxTokens, long maxTimeMs, int maxRetries) {
        return tasks.computeIfAbsent(taskId, k -> new BudgetScope(BudgetLevel.TASK, maxTokens, maxTimeMs, maxRetries));
    }

    public BudgetScope step(String stepId, long maxTokens, long maxTimeMs, int maxRetries) {
        return steps.computeIfAbsent(stepId, k -> new BudgetScope(BudgetLevel.STEP, maxTokens, maxTimeMs, maxRetries));
    }

    public BudgetScope subAgent(String subAgentId, long maxTokens, long maxTimeMs, int maxRetries) {
        return subAgents.computeIfAbsent(subAgentId,
                k -> new BudgetScope(BudgetLevel.SUBAGENT, maxTokens, maxTimeMs, maxRetries));
    }

    /** 全局是否仍可继续（快速预检）。 */
    public boolean canProceed() {
        return !global.exhausted();
    }

    /**
     * 记一次子作用域的 token 消耗，并向上累计到全局。
     *
     * @param scope   子作用域（可为 null，仅记全局）
     * @param tokens  消耗量
     */
    public void record(BudgetScope scope, long tokens) {
        if (scope != null) {
            scope.addTokens(tokens);
        }
        global.addTokens(tokens);
    }

    /** 全局耗尽原因。 */
    public Optional<String> globalExhaustedReason() {
        return Optional.ofNullable(global.exhaustedReason());
    }

    /** 汇总快照（用于每轮状态快照/日志）。 */
    public Map<String, Object> snapshot() {
        return Map.of(
                "global", Map.of("tokens", global.usedTokens(), "elapsedMs", global.elapsedMs()),
                "tasks", tasks.size(),
                "steps", steps.size(),
                "subAgents", subAgents.size());
    }
}
