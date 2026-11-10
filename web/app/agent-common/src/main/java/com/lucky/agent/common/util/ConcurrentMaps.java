package com.lucky.agent.common.util;

import java.util.Map;

/**
 * 并发集合整批替换的小工具（台账 #39）。
 *
 * <p>把 {@code registry.clear(); registry.putAll(next);} 换成 {@link #replaceAll}：
 * clear 与 putAll 之间有一个「注册表为空」的窗口，registry 是被并发读的
 * （工具发现、技能匹配、健康探活都直接遍历它），窗口期内所有条目瞬时消失，
 * 读到的列表为空、revision 却在涨。diff 替换保证任意中间时刻读者看到的至多是
 * 「个别旧条目尚未剔除」，绝不会看到空表。</p>
 */
public final class ConcurrentMaps {

    private ConcurrentMaps() {
    }

    /**
     * 用 {@code next} 的内容替换 {@code target} 的内容。
     *
     * <p>顺序固定为「先写新（覆盖已有键）、后删旧键」：多出来的旧条目在剔除前
     * 仍然可见，这是安全的 —— 它们在替换前就存在。{@code target} 须是并发容器。</p>
     *
     * @param target 并发 Map（原地修改）
     * @param next   期望的完整内容
     * @param <K>    键类型
     * @param <V>    值类型
     */
    public static <K, V> void replaceAll(Map<K, V> target, Map<K, V> next) {
        next.forEach(target::put);
        target.keySet().retainAll(next.keySet());
    }
}
