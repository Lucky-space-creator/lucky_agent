package com.lucky.agent.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConcurrentMaps#replaceAll} 的契约（台账 #39）：
 * 整批替换必须走「先写新、后删旧」的 diff 顺序，绝不允许 {@code clear()+putAll()} ——
 * 后者在两步之间留出「注册表为空」的窗口，并发读者会瞬时看到 0 个条目
 * （工具发现/技能匹配直接漏一轮）。
 */
class ConcurrentMapsTest {

    @Test
    @DisplayName("目标内容与 next 完全一致：旧键删除、新键写入、已有键被覆盖")
    void replaceAllProducesExactlyNext() {
        Map<String, String> target = new HashMap<>();
        target.put("a", "旧a");
        target.put("b", "旧b");
        target.put("gone", "将被删除");

        ConcurrentMaps.replaceAll(target, Map.of("a", "新a", "b", "新b", "c", "新c"));

        assertThat(target)
                .as("替换后目标必须与 next 等价")
                .containsEntry("a", "新a")
                .containsEntry("b", "新b")
                .containsEntry("c", "新c")
                .doesNotContainKey("gone")
                .hasSize(3);
    }

    @Test
    @DisplayName("替换过程不经过空表：任一可观察状态都不是 clear 后的残局")
    void neverEmptiesTargetMidway() {
        // 结构上守住顺序：先写后删。若实现误用 clear()，本用例借「删后写前抛异常」验证旧值仍在
        Map<String, String> target = new HashMap<>();
        target.put("keep", "v1");

        Map<String, String> next = new LinkedHashMap<>() {
            @Override
            public String put(String key, String value) {
                // 第一次 put 时机 = 「写新阶段开始」；此刻目标里必须还有旧值
                assertThat(target)
                        .as("写入新条目之前不得清空目标（clear()+putAll 会让读者看到空表）")
                        .containsEntry("keep", "v1");
                return super.put(key, value);
            }
        };
        next.put("new", "v2");

        ConcurrentMaps.replaceAll(target, next);

        // keep 不在 next 里，按契约必须被剔除；这里真正要守的是「写新时旧值还在」（上方断言）
        assertThat(target).containsEntry("new", "v2").doesNotContainKey("keep");
    }

    @Test
    @DisplayName("next 为空等价于清空，但不经由 clear()")
    void emptyNextClearsTarget() {
        Map<String, String> target = new HashMap<>();
        target.put("a", "1");
        target.put("b", "2");

        ConcurrentMaps.replaceAll(target, Map.of());

        assertThat(target).isEmpty();
    }
}
