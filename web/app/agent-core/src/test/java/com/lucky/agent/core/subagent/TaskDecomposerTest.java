package com.lucky.agent.core.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务拆分判定测试（流程图 C 节点：是否需要拆成小任务）。
 */
class TaskDecomposerTest {

    private final TaskDecomposer decomposer = new TaskDecomposer();

    @Test
    void testExplicitDecomposeIntent() {
        assertTrue(decomposer.isComplex("帮我拆解这个模块的实现步骤"));
        assertTrue(decomposer.isComplex("先出个计划再动手"));
        assertTrue(decomposer.isComplex("用 subagent 并行处理"));
    }

    @Test
    void testMultiSignalTask() {
        assertTrue(decomposer.isComplex("读取日志文件并统计错误数，然后生成一份汇总报告"));
        assertTrue(decomposer.isComplex("分别重构 service 层与 controller 层"));
    }

    @Test
    void testSingleIntentTaskNotComplex() {
        assertFalse(decomposer.isComplex("这个文件里有多少行代码"));
        assertFalse(decomposer.isComplex("帮我看看这段代码"));
        assertFalse(decomposer.isComplex(null));
    }

    @Test
    void testLongButSingleSignalNotComplex() {
        assertFalse(decomposer.isComplex("请详细解释一下这个 Spring Boot 应用的启动流程是怎样运转的"),
                "仅命中一个多目标信号词且无显式拆解意图时不应判定为复杂任务");
    }
}
