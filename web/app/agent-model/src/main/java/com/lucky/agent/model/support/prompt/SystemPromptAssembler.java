package com.lucky.agent.model.support.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示词组装器：静态/动态分界（D17）。
 *
 * <p>静态部分（基座/人格/阶段/权限约束）保持字节级稳定为可缓存前缀；
 * 动态部分（记忆/会话/环境）在边界标记后实时组装。五级覆盖优先级固定。</p>
 */
public class SystemPromptAssembler {

    private final List<String> staticLayers = new ArrayList<>();
    private final List<String> dynamicLayers = new ArrayList<>();

    /** 基座层：框架身份与总规则（恒定）。 */
    public SystemPromptAssembler base(String base) {
        staticLayers.add(base);
        return this;
    }

    /** 角色/人格层。 */
    public SystemPromptAssembler persona(String persona) {
        staticLayers.add(persona);
        return this;
    }

    /** 阶段指令层（PLAN/ACT/ASK 各不同）。 */
    public SystemPromptAssembler phase(String phase) {
        staticLayers.add(phase);
        return this;
    }

    /** 权限约束层。 */
    public SystemPromptAssembler permission(String permission) {
        staticLayers.add(permission);
        return this;
    }

    /** 召回上下文层（动态，记忆/Skill）。 */
    public SystemPromptAssembler memory(String memory) {
        dynamicLayers.add(memory);
        return this;
    }

    /** 组装：静态前缀 + 动态边界 + 动态后缀。 */
    public String assemble() {
        StringBuilder sb = new StringBuilder();
        for (String layer : staticLayers) {
            if (layer != null && !layer.isBlank()) {
                sb.append(layer).append('\n');
            }
        }
        sb.append(PromptBoundary.DYNAMIC_BOUNDARY).append('\n');
        for (String layer : dynamicLayers) {
            if (layer != null && !layer.isBlank()) {
                sb.append(layer).append('\n');
            }
        }
        return sb.toString();
    }

    /** 仅静态前缀（不含动态部分，供缓存标识）。 */
    public String staticPrefix() {
        StringBuilder sb = new StringBuilder();
        for (String layer : staticLayers) {
            if (layer != null && !layer.isBlank()) {
                sb.append(layer).append('\n');
            }
        }
        return sb.toString();
    }
}
