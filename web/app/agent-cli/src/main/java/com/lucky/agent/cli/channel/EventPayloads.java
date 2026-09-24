package com.lucky.agent.cli.channel;

import com.lucky.agent.common.dto.AgentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件 payload 的归一化工具。
 *
 * <p>存在的理由：内核在**同进程**内发布事件，payload 里放的是强类型对象
 * （如 {@link AgentEvent.OptionItem} 记录）；而一旦事件经过 JSON 序列化再回来
 * （SSE 回放、headless {@code stream-json} 转存），同样的字段会变成 {@code Map}。
 * 消费端若只判 {@code instanceof Map}，在真实内核链路上会**静默拿到空结果**——
 * 这类「两种形状」的坑集中在这里处理一次，避免每个消费点各写一遍、各漏一遍。</p>
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /**
     * 把 {@code options} 事件的 payload 归一化为选项 Map 列表。
     *
     * <p>统一输出键：{@code id} / {@code label} / {@code detail} / {@code recommended}（恒为 Boolean）。
     * 非列表或无法识别的元素一律跳过，不抛异常。</p>
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> optionList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            } else if (o instanceof AgentEvent.OptionItem item) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", item.id());
                m.put("label", item.label());
                m.put("detail", item.detail());
                // 恒为 Boolean，避免下游 Boolean.TRUE.equals(null) 与 null 打印的分叉
                m.put("recommended", Boolean.TRUE.equals(item.recommended()));
                result.add(m);
            }
        }
        return result;
    }
}
