package com.lucky.agent.model.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.lucky.agent.common.exception.AgentException;

import java.util.Locale;

/**
 * 全局推理深度枚举（配置文件中存枚举名，如 {@code "BALANCED"}）。
 *
 * <p>深度由低到高：模型默认 / 快速 / 标准 / 深入 / 极致，接入模型请求时映射为
 * OpenAI {@code reasoning_effort} 与 Anthropic {@code thinking} 预算。
 * 兼容旧版数字 1–5 的反序列化（settings.json 曾以整数存储）。</p>
 */
public enum InferenceDepth {

    /**
     * 不指定思考参数：交由模型自身默认决定，<b>并非「关闭思考」</b>。
     *
     * <p>实现上不发送 {@code reasoning_effort} / {@code thinking}。注意各厂商默认值不同：
     * DeepSeek 思考模式默认开启且 effort 默认 high，Qwen3.8 默认 xhigh；因此在
     * OpenAI 兼容端点上本档仍会思考，只是档位不由框架指定。真正关闭需厂商专用开关
     * （{@code thinking:{"type":"disabled"}} / {@code enable_thinking:false}），当前未接入。</p>
     */
    OFF(1),

    /** 快速：轻量推理，适合简单问答。 */
    QUICK(2),

    /** 标准：均衡推理（默认）。 */
    BALANCED(3),

    /** 深入：深度思考，适合复杂任务。 */
    DEEP(4),

    /** 极致：最大思考预算，最耗 token。 */
    MAXIMUM(5);

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 5;

    private final int level;

    InferenceDepth(int level) {
        this.level = level;
    }

    /** 数值档位（1–5），用于内部映射与旧版配置兼容。 */
    public int level() {
        return level;
    }

    /** 从数值档位（1–5）解析，越界抛业务异常。 */
    public static InferenceDepth fromLevel(int level) {
        for (InferenceDepth depth : values()) {
            if (depth.level == level) {
                return depth;
            }
        }
        throw new AgentException("INVALID_INFERENCE_DEPTH",
                "推理深度需在 " + MIN_LEVEL + "–" + MAX_LEVEL + " 之间：实际 " + level);
    }

    /**
     * 从配置文件值解析：接受枚举名（忽略大小写）或旧版数字（1–5 及数字字符串）。
     * 无法解析时抛业务异常，避免静默降级。
     */
    @JsonCreator
    public static InferenceDepth fromValue(Object value) {
        if (value instanceof Number number) {
            return fromLevel(number.intValue());
        }
        if (value instanceof String text) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);
            for (InferenceDepth depth : values()) {
                if (depth.name().equals(normalized)) {
                    return depth;
                }
            }
            if (normalized.matches("\\d+")) {
                return fromLevel(Integer.parseInt(normalized));
            }
            throw new AgentException("INVALID_INFERENCE_DEPTH", "未知推理深度：" + text);
        }
        throw new AgentException("INVALID_INFERENCE_DEPTH", "推理深度配置无法解析：" + value);
    }

    /**
     * 默认推理深度（关闭）。
     *
     * <p>推理深度「只做参考，不强制」：默认关闭即不发送 {@code reasoning_effort}/{@code thinking}，
     * 让模型使用自身能力（深度思考模型自行思考、普通模型不思考）。用户显式选择深度时才映射发送。</p>
     */
    public static InferenceDepth defaultValue() {
        return OFF;
    }
}
