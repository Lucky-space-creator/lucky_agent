package com.lucky.agent.core.util.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次会话运行指标快照（P3 透明面板数据源）。
 *
 * <p>由 {@link MetricsCollector} 从本会话 {@code Flux<AgentEvent>} 聚合得到，
 * 不引入额外存储；仅统计内存运行期计数，会话结束即丢弃。计数器线程安全。</p>
 *
 * <p><b>本轮 vs 会话累计是两个口径，不要混用</b>：</p>
 * <ul>
 *   <li><b>本轮计数器</b>（{@code tokenUsed}/{@code modelCalls}/{@code toolCounts}/…）：
 *       每次用户发消息（{@link #beginTurn()}）归零，只反映「这一条消息跑了多少轮、花了多少」。
 *       界面状态条与透明面板的「推理轮次」必须用它 —— 否则第二条消息会显示「9 轮」，
 *       用户看到的不是这条消息的成本。</li>
 *   <li><b>会话累计</b>（{@code sessionTokenUsed}/{@code sessionModelCalls}）：跨轮累加，
 *       只用于「整个会话一共花了多少」这类汇总视图。</li>
 * </ul>
 */
public class SessionMetrics {

    /** 本轮 token 用量（每次模型调用增量累加）。 */
    private final AtomicLong tokenUsed = new AtomicLong();
    /** 本轮输入 token。 */
    private final AtomicLong inputTokens = new AtomicLong();
    /** 本轮输出 token。 */
    private final AtomicLong outputTokens = new AtomicLong();
    /** 本轮模型调用次数（每次 model.chat 计 1，即本轮推理轮次）。 */
    private final AtomicLong modelCalls = new AtomicLong();
    /** 会话累计 token（跨轮，不随 beginTurn 归零）。 */
    private final AtomicLong sessionTokenUsed = new AtomicLong();
    /** 会话累计模型调用次数（跨轮）。 */
    private final AtomicLong sessionModelCalls = new AtomicLong();
    /** 当前是第几轮用户消息（从 1 开始）。 */
    private final AtomicLong turnIndex = new AtomicLong();
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
    /** 本轮指标开始时间（epoch ms），用于计算本轮运行时长。 */
    private volatile long startAt;
    /** 本轮结束时间（epoch ms）；0 表示仍在运行，用于把时长冻结在真实结束时刻。 */
    private volatile long endAt;
    /** 子代理进度（taskId -> done/total/status）。 */
    private final ConcurrentHashMap<String, TaskProgress> subAgents = new ConcurrentHashMap<>();

    public void addToken(long used) {
        tokenUsed.addAndGet(used);
        sessionTokenUsed.addAndGet(used);
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
        if (startAt <= 0) {
            return 0;
        }
        // 收到 stop 后冻结：否则面板上的「本轮时长」会在任务结束后继续往上涨
        long end = endAt > 0 ? endAt : System.currentTimeMillis();
        return Math.max(0, end - startAt);
    }

    /** 本轮结束（收到 stop 事件）：冻结时长，并拒绝后续重复调用。 */
    public void finishTurn() {
        if (endAt == 0) {
            endAt = System.currentTimeMillis();
        }
    }

    public long totalToolOk() {
        return toolOk.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public long totalToolFail() {
        return toolFail.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public void incModelCalls() {
        modelCalls.incrementAndGet();
        sessionModelCalls.incrementAndGet();
    }

    /**
     * 开启新一轮用户消息：本轮计数器全部归零，会话累计保持不变。
     *
     * <p>由 {@link MetricsCollector#start(String)} 在每次提交时调用。缓存命中/未命中
     * 属于全局缓存器读数（快照时直接覆盖），归零无意义但也无害。</p>
     */
    public void beginTurn() {
        tokenUsed.set(0);
        inputTokens.set(0);
        outputTokens.set(0);
        modelCalls.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        toolCounts.clear();
        toolOk.clear();
        toolFail.clear();
        errors.set(0);
        skillInvokes.set(0);
        mcpInvokes.set(0);
        subAgents.clear();
        startAt = System.currentTimeMillis();
        endAt = 0;
        turnIndex.incrementAndGet();
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
        snap.put("turnIndex", turnIndex.get());
        snap.put("sessionTokenUsed", sessionTokenUsed.get());
        snap.put("sessionModelCalls", sessionModelCalls.get());
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
