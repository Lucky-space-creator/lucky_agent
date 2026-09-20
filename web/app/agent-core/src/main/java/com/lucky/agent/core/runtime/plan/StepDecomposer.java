package com.lucky.agent.core.runtime.plan;

import com.lucky.agent.core.runtime.contract.RuntimeContext;

import java.util.List;

/**
 * 步骤拆解器：把「拆成步骤？」从主循环的硬编码判断下沉为可替换的策略组件。
 *
 * <p>默认提供启发式实现（{@link HeuristicStepDecomposer}），后续可替换为
 * LLM 驱动的拆解（复用 agent-model 的 ChatModel），而 {@code DecomposeTool} 与主循环无需改动。</p>
 */
public interface StepDecomposer {

    /**
     * 拆解目标为有序步骤。
     *
     * @param goal 目标
     * @param ctx  运行时上下文（可携带约束、已有产物等）
     * @return 步骤列表（可为空表示无需拆解）
     */
    List<Step> decompose(String goal, RuntimeContext ctx);

    /**
     * 单个步骤。
     *
     * @param index  序号（1 起）
     * @param title  标题
     * @param detail 细节
     */
    record Step(int index, String title, String detail) {

        public static Step of(int index, String title) {
            return new Step(index, title, null);
        }
    }
}
