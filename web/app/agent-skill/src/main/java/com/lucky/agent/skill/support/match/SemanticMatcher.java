package com.lucky.agent.skill.support.match;

import com.lucky.agent.skill.service.SkillMatcher;
import com.lucky.agent.skill.repository.dto.SkillDef;
import com.lucky.agent.skill.repository.dto.SkillMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 语义 Top-K 匹配（本机零依赖实现）。
 *
 * <p>复用 agent-memory 的轻量文本相似度思路：ASCII 词 + 中文 bigram 抽取查询与候选
 * 的 token 集合，按重叠率打分；触发词精确命中额外加权。向量匹配可在后续替换本实现，
 * 对 {@code SkillRegistry}/{@code ToolGateway} 无侵入（策略模式）。</p>
 */
public class SemanticMatcher implements SkillMatcher {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_]+");

    /** 触发词最小长度：过短的词歧义过大，不作「强相关」判据。 */
    private static final int TRIGGER_MIN_LEN = 4;

    /** 触发词停用词表：常见通用词会污染召回，命中不代表意图相关，直接剔除。 */
    private static final Set<String> TRIGGER_STOP_WORDS = Set.of(
            "create", "make", "you", "and", "the", "for", "your", "with", "this", "that",
            "art", "asks", "avoid", "use", "file", "tool", "skills");

    /** 触发词命中权重：命中记 0.6 基础分，再叠加描述重叠分，而非直接给满分压过语义分。 */
    private static final double TRIGGER_HIT_WEIGHT = 0.6;

    @Override
    public List<SkillMatch> match(List<SkillDef> skills, String query, int topK) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (SkillDef skill : skills) {
            if (skill == null) {
                continue;
            }
            double score = score(query, skill);
            if (score > 0) {
                scored.add(new Scored(skill, score));
            }
        }
        int limit = topK <= 0 ? 5 : topK;
        return scored.stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
                .limit(limit)
                .map(s -> new SkillMatch(s.skill, s.score))
                .toList();
    }

    private double score(String query, SkillDef skill) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        StringBuilder candidate = new StringBuilder();
        if (skill.description() != null) {
            candidate.append(skill.description()).append(' ');
        }
        if (skill.name() != null) {
            candidate.append(skill.name()).append(' ');
        }
        if (skill.triggers() != null) {
            skill.triggers().forEach(t -> candidate.append(t).append(' '));
        }
        // 触发词命中：仅对长度达标且非停用词的触发词计分，作加权而非直接满分
        double triggerScore = 0;
        if (skill.triggers() != null) {
            String lower = query.toLowerCase();
            for (String trigger : skill.triggers()) {
                if (trigger == null || trigger.isBlank()) {
                    continue;
                }
                String t = trigger.trim().toLowerCase();
                if (t.length() < TRIGGER_MIN_LEN || TRIGGER_STOP_WORDS.contains(t)) {
                    continue;
                }
                if (lower.contains(t)) {
                    triggerScore = Math.max(triggerScore, TRIGGER_HIT_WEIGHT);
                }
            }
        }
        List<String> qTokens = tokenize(query);
        List<String> cTokens = tokenize(candidate.toString());
        if (qTokens.isEmpty() || cTokens.isEmpty()) {
            return triggerScore;
        }
        Set<String> cSet = new HashSet<>(cTokens);
        long overlap = qTokens.stream().filter(cSet::contains).count();
        double overlapScore = (double) overlap / qTokens.size();
        return Math.min(1.0, triggerScore + 0.5 * overlapScore);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase();
        Matcher matcher = ASCII_WORD.matcher(lower);
        while (matcher.find()) {
            if (matcher.group().length() >= 2) {
                tokens.add(matcher.group());
            }
        }
        for (int i = 0; i + 1 < lower.length(); i++) {
            char c0 = lower.charAt(i);
            char c1 = lower.charAt(i + 1);
            if (isCjk(c0) && isCjk(c1)) {
                tokens.add("" + c0 + c1);
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private record Scored(SkillDef skill, double score) {
    }
}
