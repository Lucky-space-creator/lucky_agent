package com.lucky.agent.model.support.prompt;

/**
 * 提示词静态/动态分界常量（八项差距 D17）。
 *
 * <p>静态可缓存前缀与动态部分以固定边界标记分隔；{@code cache_control} 断点按模型供应商能力在 Phase 2 接入。</p>
 */
public final class PromptBoundary {

    /** 静态 / 动态提示词分界标记。 */
    public static final String DYNAMIC_BOUNDARY = "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__";

    private PromptBoundary() {
    }

    /**
     * 五级覆盖优先级：Override &gt; Coordinator &gt; Agent &gt; Custom &gt; Default。
     */
    public enum Priority {
        OVERRIDE(0),
        COORDINATOR(1),
        AGENT(2),
        CUSTOM(3),
        DEFAULT(4);

        private final int order;

        Priority(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }
}
