package com.lucky.agent.skill;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.skill.support.gateway.SkillToolAdapter;
import com.lucky.agent.skill.service.SkillMatcher;
import com.lucky.agent.skill.service.SkillRegistry;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.repository.dto.SkillMatch;
import com.lucky.agent.skill.config.SkillProperties;
import com.lucky.agent.skill.support.resolve.DependencyResolver;
import com.lucky.agent.skill.support.sandbox.SkillSandbox;

import java.util.List;

/**
 * Skill 工具来源：经 core ToolGateway 注入编排引擎。
 *
 * <p>按任务目标语义召回 Top-K 命中的启用 Skill（依赖齐全）适配为工具——非全量注入，省 token；
 * {@code goal} 为空（无明确意图）或语义召回为空时，退化为注入 Top-K 个启用 Skill，保证已启用
 * Skill 始终对模型可见、可被调用（否则等于「注册了却永远调不到」，监控面板也收不到 skill_invoke）。
 * 工具集每次实时构建，热插拔（启停/增删）下一轮推理即生效。</p>
 */
public class SkillToolSource implements ToolSource {

    private final SkillRegistry registry;
    private final SkillMatcher matcher;
    private final SkillSandbox sandbox;
    private final DependencyResolver resolver;
    private final SkillProperties properties;

    public SkillToolSource(SkillRegistry registry, SkillMatcher matcher, SkillSandbox sandbox,
                           DependencyResolver resolver, SkillProperties properties) {
        this.registry = registry;
        this.matcher = matcher;
        this.sandbox = sandbox;
        this.resolver = resolver;
        this.properties = properties;
    }

    @Override
    public String namespace() {
        return "skill";
    }

    @Override
    public List<Tool> tools(String workspaceId, String goal) {
        List<SkillDef> all = registry.list();
        List<SkillDef> eligible = registry.listEnabled().stream()
                .filter(s -> resolver.resolve(s, all))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<SkillMatch> matches;
        if (goal == null || goal.isBlank()) {
            // 无明确意图：按 Top-K 限量注入全部启用 Skill，避免启用较多时全量上送、撑爆上下文
            matches = eligible.stream()
                    .limit(Math.max(1, properties.topK()))
                    .map(s -> new SkillMatch(s, 1.0))
                    .toList();
        } else {
            matches = matcher.match(eligible, goal, properties.topK());
            // 语义召回为空（自由语言提问通常不命中触发词 / CJK 二元组）时，回退注入启用 Skill
            // （Top-K 上限），保证已启用 Skill 始终对模型可见、可被调用；否则这些 Skill 等于
            // 「注册了却永远调不到」，监控面板也永远收不到 skill_invoke 事件。
            if (matches.isEmpty()) {
                matches = eligible.stream()
                        .limit(Math.max(1, properties.topK()))
                        .map(s -> new SkillMatch(s, 1.0))
                        .toList();
            }
        }
        return matches.stream()
                .map(m -> (Tool) new SkillToolAdapter(m.skill(), sandbox))
                .toList();
    }
}
