package com.lucky.agent.skill.support.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolAnnotations;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.support.sandbox.SkillSandbox;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Skill → 工具适配器：把命中的 Skill 适配为 common {@link Tool}，无缝进入 core 的 ToolGateway。
 *
 * <p>工具名固定为 {@code skill.<id>}（引擎据此发布 {@code skill_invoke} 事件并计入透明面板）；
 * 纯指令型 Skill 标注只读（可并行），脚本型标注写（串行）。执行委托 {@link SkillSandbox}，
 * 错误以 {@link ToolResult#isError()} 返回，不向上抛异常。</p>
 */
public class SkillToolAdapter implements Tool {

    private final SkillDef skill;
    private final SkillSandbox sandbox;

    public SkillToolAdapter(SkillDef skill, SkillSandbox sandbox) {
        this.skill = skill;
        this.sandbox = sandbox;
    }

    public SkillDef skill() {
        return skill;
    }

    @Override
    public String name() {
        return "skill." + skill.id();
    }

    @Override
    public String description() {
        return skill.description();
    }

    @Override
    public ToolAnnotations annotations() {
        // 纯指令型只读（可并行）；脚本型写（串行，含沙箱/ASK 语义）
        if (skill.entry() == null || skill.entry().isBlank()) {
            return ToolAnnotations.readOnlyTool();
        }
        return ToolAnnotations.writableTool();
    }

    @Override
    public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
        return Mono.just(sandbox.run(skill, ctx, args));
    }
}
