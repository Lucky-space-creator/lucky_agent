package com.lucky.agent.skill;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.skill.adapter.SkillToolAdapter;
import com.lucky.agent.skill.api.SkillMatcher;
import com.lucky.agent.skill.api.SkillRegistry;
import com.lucky.agent.skill.api.dto.SkillDef;
import com.lucky.agent.skill.api.dto.SkillMatch;
import com.lucky.agent.skill.config.SkillProperties;
import com.lucky.agent.skill.resolve.DependencyResolver;
import com.lucky.agent.skill.sandbox.SkillSandbox;

import java.util.List;

/**
 * Skill 工具来源：经 core ToolGateway 注入编排引擎。
 *
 * <p>按任务目标语义召回 Top-K 命中的启用 Skill（依赖齐全）适配为工具——非全量注入，
 * 省 token；{@code goal} 为空（无明确意图）时退化为注入全部启用 Skill（数量有限）。
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
        List<SkillMatch> matches;
        if (goal == null || goal.isBlank()) {
            // 无明确意图：仍按 Top-K 限量注入，避免启用 Skill 较多时全量上送、撑爆上下文
            matches = eligible.stream()
                    .limit(Math.max(1, properties.topK()))
                    .map(s -> new SkillMatch(s, 1.0))
                    .toList();
        } else {
            matches = matcher.match(eligible, goal, properties.topK());
        }
        return matches.stream()
                .map(m -> (Tool) new SkillToolAdapter(m.skill(), sandbox))
                .toList();
    }
}
