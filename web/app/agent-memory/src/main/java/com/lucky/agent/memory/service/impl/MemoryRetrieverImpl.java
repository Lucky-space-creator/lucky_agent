package com.lucky.agent.memory.service.impl;

import com.lucky.agent.memory.service.MemoryRetriever;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.api.dto.RecallResult;
import com.lucky.agent.memory.config.MemoryProperties;
import com.lucky.agent.memory.support.pipeline.DecayManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双轨召回实现（用户轨 + 平台轨，用户偏好优先）。
 *
 * <p>MVP 采用轻量文本相似度（ASCII 词 + 中文 bigram 重叠），向量索引在 Phase 2 引入。
 * 召回时对用户轨按「工作空间」过滤（防止跨项目串扰），并施加时间衰减：
 * 长期未印证的低置信记忆被遗忘，同分条目按时间近者优先（矛盾点时间近优先）。</p>
 */
@Service
public class MemoryRetrieverImpl implements MemoryRetriever {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_]+");

    private final MemoryStore userStore;
    private final MemoryStore platformStore;
    private final DecayManager decayManager;
    private final MemoryProperties properties;

    public MemoryRetrieverImpl(@Qualifier("userMemoryStore") MemoryStore userStore,
                               @Qualifier("platformMemoryStore") MemoryStore platformStore,
                               DecayManager decayManager,
                               MemoryProperties properties) {
        this.userStore = userStore;
        this.platformStore = platformStore;
        this.decayManager = decayManager;
        this.properties = properties;
    }

    @Override
    public RecallResult recall(String userId, String query, int topK) {
        List<MemoryEntry> userEntries = recallUser(userId, query, topK);
        List<MemoryEntry> platformEntries = recallPlatform(query, topK);
        List<MemoryEntry> merged = new ArrayList<>(userEntries);
        merged.addAll(platformEntries);
        return RecallResult.of(query, merged);
    }

    @Override
    public RecallResult recall(String userId, String workspaceId, String query, int topK) {
        List<MemoryEntry> userEntries = recallUser(userId, workspaceId, query, topK);
        List<MemoryEntry> platformEntries = recallPlatform(query, topK);
        List<MemoryEntry> merged = new ArrayList<>(userEntries);
        merged.addAll(platformEntries);
        return RecallResult.of(query, merged);
    }

    @Override
    public List<MemoryEntry> recallUser(String userId, String query, int topK) {
        return rank(userStore.loadAll(userId), query, topK);
    }

    @Override
    public List<MemoryEntry> recallUser(String userId, String workspaceId, String query, int topK) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return recallUser(userId, query, topK);
        }
        return rank(userStore.loadAll(userId, workspaceId), query, topK);
    }

    @Override
    public List<MemoryEntry> recallPlatform(String query, int topK) {
        return rank(platformStore.loadAll(null), query, topK);
    }

    private List<MemoryEntry> rank(List<MemoryEntry> entries, String query, int topK) {
        long now = System.currentTimeMillis();
        List<Scored> scored = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            double score = similarity(query, entry.content());
            if (score <= 0) {
                continue;
            }
            // 时间衰减：低置信 + 长期未印证 → 遗忘（P1-9 衰减机制接入召回）
            long ageDays = ageDays(entry.ts(), now);
            double confidence = decayManager.apply(entry.confidence(), ageDays);
            if (decayManager.shouldForget(confidence, properties.forgetThreshold())) {
                continue;
            }
            Scored s = new Scored(entry, score, confidence, entry.ts() == null ? 0 : parseTs(entry.ts()));
            scored.add(s);
        }
        return scored.stream()
                // 相似度 > 置信度 > 时间近者优先（矛盾点以时间近为准）
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed()
                        .thenComparing(s -> s.confidence, Comparator.reverseOrder())
                        .thenComparing(s -> s.tsMillis, Comparator.reverseOrder()))
                .limit(topK)
                .map(s -> s.entry)
                .toList();
    }

    private long ageDays(String ts, long nowMillis) {
        try {
            if (ts == null || ts.isBlank()) {
                return 0;
            }
            return ChronoUnit.DAYS.between(Instant.parse(ts), Instant.ofEpochMilli(nowMillis));
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseTs(String ts) {
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private double similarity(String query, String content) {
        if (query == null || content == null) {
            return 0;
        }
        List<String> qTokens = tokenize(query);
        List<String> cTokens = tokenize(content);
        if (qTokens.isEmpty()) {
            return 0;
        }
        Set<String> cSet = Set.copyOf(cTokens);
        long overlap = qTokens.stream().filter(cSet::contains).count();
        return (double) overlap / qTokens.size();
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
        // 中文 bigram
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

    private record Scored(MemoryEntry entry, double score, double confidence, long tsMillis) {
    }
}