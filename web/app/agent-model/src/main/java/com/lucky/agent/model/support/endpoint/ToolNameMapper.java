package com.lucky.agent.model.support.endpoint;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内部工具名 ⇄ 模型厂商安全名的双向映射（每次模型调用构建一个实例）。
 *
 * <p><b>为什么需要映射</b>：内部工具名采用点分命名空间（{@code file.read} / {@code skill.pdf}
 * / {@code mcp.<serverId>.<tool>}），用于事件、缓存指纹、权限裁决与契约文档；但 OpenAI / Anthropic
 * 兼容端点强制函数名匹配 {@code ^[a-zA-Z0-9_-]+$}，带点号会直接被拒：
 * {@code Invalid 'tools[0].function.name': string does not match pattern}（HTTP 400）。</p>
 *
 * <p>映射<b>只在发给厂商的线上报文生效</b>：发送前把内部名换成安全名，收到模型的
 * {@code tool_calls} / {@code tool_use} 后再还原回内部名。这样内部契约
 * （{@code ReactEngine} 的 {@code startsWith("skill.")} 前缀分发、{@code toOpType} 精确匹配、
 * 工具缓存指纹）均不受影响。</p>
 *
 * <p>名称为纯 ASCII 安全字符时保持原样；冲突时追加 {@code _1}、{@code _2} 后缀保证一一对应。</p>
 */
@Slf4j
public class ToolNameMapper {

    private static final Pattern SAFE = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern ILLEGAL = Pattern.compile("[^a-zA-Z0-9_-]");

    private final Map<String, String> internalToWire = new LinkedHashMap<>();
    private final Map<String, String> wireToInternal = new LinkedHashMap<>();

    /**
     * 登记内部工具名并返回其线上安全名（同一内部名重复登记返回同一结果）。
     */
    public String register(String internal) {
        if (internal == null || internal.isBlank()) {
            return internal;
        }
        String registered = internalToWire.get(internal);
        if (registered != null) {
            return registered;
        }
        String base = SAFE.matcher(internal).matches() ? internal : ILLEGAL.matcher(internal).replaceAll("_");
        String wire = base;
        int suffix = 1;
        while (wireToInternal.containsKey(wire)) {
            wire = base + "_" + suffix++;
        }
        if (!wire.equals(internal)) {
            log.debug("工具名线上映射：{} → {}", internal, wire);
        }
        internalToWire.put(internal, wire);
        wireToInternal.put(wire, internal);
        return wire;
    }

    /**
     * 线上名还原为内部名；未登记时原样返回。
     * （厂商可能回显未登记的名字，原样返回可保证调用不丢，由网关报「未知工具」。）
     */
    public String internal(String wire) {
        if (wire == null) {
            return null;
        }
        return wireToInternal.getOrDefault(wire, wire);
    }
}
