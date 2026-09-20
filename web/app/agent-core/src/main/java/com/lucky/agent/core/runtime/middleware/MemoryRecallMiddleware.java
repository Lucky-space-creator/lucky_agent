package com.lucky.agent.core.runtime.middleware;

import com.lucky.agent.core.runtime.contract.RuntimeContext;
import com.lucky.agent.core.runtime.memory.MemoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆召回中间件：首次调用 LLM 前召回相关长期记忆，写入运行时属性（键 {@link #ATTR_RECALLED}），
 * 由提示词装配层消费。
 *
 * <p>只召回一次（同一任务内），避免每轮重复检索放大延迟；召回失败不影响主链路（记忆是增强而非依赖）。</p>
 */
public class MemoryRecallMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallMiddleware.class);

    /** 召回文本属性键。 */
    public static final String ATTR_RECALLED = "recalledMemory";

    /** 是否已召回的标记键。 */
    public static final String ATTR_RECALLED_DONE = "memoryRecalled";

    private final MemoryPort memory;

    public MemoryRecallMiddleware(MemoryPort memory) {
        this.memory = memory;
    }

    @Override
    public int order() {
        return MiddlewareOrder.MEMORY_RECALL;
    }

    @Override
    public void beforeLlm(MiddlewareContext ctx) {
        RuntimeContext rc = ctx.runtime();
        if (Boolean.TRUE.equals(rc.attribute(ATTR_RECALLED_DONE))) {
            return;
        }
        rc.attribute(ATTR_RECALLED_DONE, Boolean.TRUE);
        try {
            String recalled = memory.recall(rc.ref(), rc.goal());
            if (recalled != null && !recalled.isBlank()) {
                rc.attribute(ATTR_RECALLED, recalled);
                log.debug("[memory] 召回 {} 字", recalled.length());
            }
        } catch (RuntimeException e) {
            // 记忆是增强能力：失败降级为无召回，不阻断主链路
            log.warn("[memory] 召回失败，降级为无召回: {}", e.getMessage());
        }
    }
}
