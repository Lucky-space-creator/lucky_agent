package com.lucky.agent.core.util.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次会话运行指标快照（P3 透明面板数据源）。
 *
 * <p>由 {@link MetricsCollector} 从本会话 {@code Flux<AgentEvent>} 聚合得到，
 * 不引入额外存储；仅统计内存运行期计数，会话结束即丢弃。计数器线程安全。</p>
 */
public class SessionMetrics {

    /** 累计 token 用量（每次模型调用增量累加，会话级总量）。 */
    private final AtomicLong tokenUsed = new AtomicLong();
    /** 累计输入 token。 */
    private final AtomicLong inputTokens = new AtomicLong();
    /** 累计输出 token。 */
    private final AtomicLong outputTokens = new AtomicLong();
    /** 模型调用次数（每次 model.chat 计 1，即推理轮次）。 */
    private final AtomicLong modelCalls = new AtomicLong();
    /** 工具结果命中缓存次数。 */
    private final AtomicLong cacheHits = new AtomicLong();
    /** 工具结果未命中缓存次数。 */
    private final AtomicLong cacheMisses = new AtomicLong();
    /** 各工具触发次数（tool -> count）。 */
    private final ConcurrentHashMap<String, AtomicLong> toolCounts = new ConcurrentHashMap<>();
    /** 各工具成功/失败计数（tool -> ok/fail）。 */
    private final ConcurrentHashMap<String, AtomicLong> toolOk = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolFail = new ConcurrentHashMap<>();
    /** 运行期错误次数（error 事件）。 */
    private final AtomicLong errors = new AtomicLong();
    /** Skill 触发次数。 */
    private final AtomicLong skillInvokes = new AtomicLong();
    /** MCP 触发次数。 */
    private final AtomicLong mcpInvokes = new AtomicLong();
    /** 最近一次使用的模型名。 */
    private volatile String lastModel;
    /** 模型上下文窗口（token）。 */
    private volatile long contextWindow;
    /** 会话指标开始时间（epoch ms），用于计算运行时长。 */
    private volatile long startAt;
    /** 子代理进度（taskId -> done/total/status）。 */
    private final ConcurrentHashMap<String, TaskProgress> subAgents = new ConcurrentHashMap<>();

    public void addToken(long used) {
        tokenUsed.addAndGet(used);
    }

    public void addInput(long input) {
        inputTokens.addAndGet(input);
    }

    public void addOutput(long output) {
        outputTokens.addAndGet(output);
    }

    public void incErrors() {
        errors.incrementAndGet();
    }

    public void setStartAt(long ts) {
        this.startAt = ts;
    }

    public long elapsedMs() {
        return startAt <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - startAt);
    }

    public long totalToolOk() {
        return toolOk.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public long totalToolFail() {
        return toolFail.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public void incModelCalls() {
        modelCalls.incrementAndGet();
    }

    public void recordCache(boolean hit) {
        if (hit) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
    }

    /** 直接对齐真实缓存计数器（全局累计，来自 FingerprintCache）。 */
    public void recordCache(long hits, long misses) {
        this.cacheHits.set(hits);
        this.cacheMisses.set(misses);
    }

    public void recordTool(String tool, boolean ok) {
        toolCounts.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        if (ok) {
            toolOk.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        } else {
            toolFail.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public void recordSkill() {
        skillInvokes.incrementAndGet();
    }

    public void recordMcp() {
        mcpInvokes.incrementAndGet();
    }

    public void setLastModel(String model) {
        this.lastModel = model;
    }

    public void setContextWindow(long window) {
        this.contextWindow = window;
    }

    public void updateSubAgent(String taskId, int done, int total, String status) {
        subAgents.compute(taskId, (k, v) -> {
            TaskProgress p = v == null ? new TaskProgress() : v;
            p.done = done;
            p.total = total;
            p.status = status;
            return p;
        });
    }

    /** 缓存命中率（0~1），无样本返回 0。 */
    public double cacheHitRate() {
        long h = cacheHits.get();
        long m = cacheMisses.get();
        long sum = h + m;
        return sum == 0 ? 0d : (double) h / sum;
    }

    /** 上下文窗口占用率（0~1），窗口未知返回 0。 */
    public double windowUsage() {
        long w = contextWindow;
        return w <= 0 ? 0d : Math.min(1d, (double) tokenUsed.get() / w);
    }

    public Map<String, Object> snapshot() {
        Map<String, Long> tools = new java.util.LinkedHashMap<>();
        toolCounts.forEach((k, v) -> tools.put(k, v.get()));
        Map<String, Map<String, Object>> subs = new java.util.LinkedHashMap<>();
        subAgents.forEach((k, v) -> subs.put(k, Map.of(
                "done", v.done, "total", v.total, "status", v.status == null ? "pending" : v.status)));
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("tokenUsed", tokenUsed.get());
        snap.put("inputTokens", inputTokens.get());
        snap.put("outputTokens", outputTokens.get());
        snap.put("modelCalls", modelCalls.get());
        snap.put("cacheHits", cacheHits.get());
        snap.put("cacheMisses", cacheMisses.get());
        snap.put("cacheHitRate", cacheHitRate());
        snap.put("toolCounts", tools);
        snap.put("toolOk", totalToolOk());
        snap.put("toolFail", totalToolFail());
        snap.put("skillInvokes", skillInvokes.get());
        snap.put("mcpInvokes", mcpInvokes.get());
        snap.put("errors", errors.get());
        snap.put("elapsedMs", elapsedMs());
        snap.put("lastModel", lastModel == null ? "" : lastModel);
        snap.put("contextWindow", contextWindow);
        snap.put("windowUsage", windowUsage());
        snap.put("subAgents", subs);
        return snap;
    }

    /** 子代理进度视图。 */
    public static class TaskProgress {
        public int done;
        public int total;
        public String status;
    }
}
