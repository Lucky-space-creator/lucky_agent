package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.ExecutionResult;
import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩中间件：token 用量跨过阈值即触发上下文压缩，替代原主循环中内联的「记忆管理（K）」步骤。
 *
 * <p>与「记忆沉淀」严格区分（重构目标 7）：</p>
 * <ul>
 *   <li><b>压缩</b>（本中间件）：任何路径都会发生，只压缩上下文，不产生长期记忆；</li>
 *   <li><b>沉淀</b>：仅在任务达成时由运行时/工具调用 {@link MemoryPort#recordLongTerm}。</li>
 * </ul>
 *
 * <p>压缩时保留结构化字段：goal / constraints / attempts / openQuestions / toolUsage，
 * 保证压缩后仍能续跑而非丢失目标与约束。</p>
 */
public class CompactionMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(CompactionMiddleware.class);

    /** 距上次压缩累计的 token 数（RuntimeContext 属性键）。 */
    public static final String ATTR_TOKENS_SINCE_COMPACT = "tokensSinceCompact";

    private final MemoryPort memory;
    private final long thresholdTokens;

    public CompactionMiddleware(MemoryPort memory, long thresholdTokens) {
        this.memory = memory;
        this.thresholdTokens = thresholdTokens;
    }

    @Override
    public int order() {
        return MiddlewareOrder.COMPACTION;
    }

    @Override
    public void afterLlm(MiddlewareContext ctx) {
        ExecutionResult result = ctx.result();
        if (result == null || result.metrics() == null || result.metrics().tokenUsed() <= 0) {
            return;
        }
        RuntimeContext rc = ctx.runtime();
        long accumulated = accumulated(rc) + result.metrics().tokenUsed();
        if (thresholdTokens > 0 && accumulated >= thresholdTokens) {
            memory.compact(rc.ref(), structured(rc, accumulated));
            rc.attribute(ATTR_TOKENS_SINCE_COMPACT, 0L);
            log.info("[compact] trace={} 累计 {} token 超阈值 {}，已压缩上下文（保留结构化字段）",
                    rc.trace() == null ? null : rc.trace().traceId(), accumulated, thresholdTokens);
        } else {
            rc.attribute(ATTR_TOKENS_SINCE_COMPACT, accumulated);
        }
    }

    private long accumulated(RuntimeContext rc) {
        Object value = rc.attribute(ATTR_TOKENS_SINCE_COMPACT);
        return (value instanceof Number n) ? n.longValue() : 0L;
    }

    private Map<String, Object> structured(RuntimeContext rc, long tokens) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(MemoryPort.Fields.GOAL, rc.goal());
        fields.put(MemoryPort.Fields.CONSTRAINTS, rc.attribute("constraints"));
        fields.put(MemoryPort.Fields.ATTEMPTS, rc.attribute("iteration"));
        fields.put(MemoryPort.Fields.OPEN_QUESTIONS, rc.attribute("openQuestions"));
        fields.put(MemoryPort.Fields.TOOL_USAGE, rc.attribute("toolUsage"));
        fields.put("tokensSinceCompact", tokens);
        return fields;
    }
}
