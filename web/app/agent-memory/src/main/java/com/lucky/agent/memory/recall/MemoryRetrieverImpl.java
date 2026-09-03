package com.lucky.agent.memory.recall;

import com.lucky.agent.memory.api.MemoryRetriever;
import com.lucky.agent.memory.api.MemoryStore;
import com.lucky.agent.memory.api.dto.MemoryEntry;
import com.lucky.agent.memory.api.dto.RecallResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双轨召回实现（用户轨 + 平台轨，用户偏好优先）。
 *
 * <p>MVP 采用轻量文本相似度（ASCII 词 + 中文 bigram 重叠），向量索引在 Phase 2 引入。</p>
 */
@Service
public class MemoryRetrieverImpl implements MemoryRetriever {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_]+");

    private final MemoryStore userStore;
    private final MemoryStore platformStore;

    public MemoryRetrieverImpl(@Qualifier("userMemoryStore") MemoryStore userStore,
                               @Qualifier("platformMemoryStore") MemoryStore platformStore) {
        this.userStore = userStore;
        this.platformStore = platformStore;
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
    public List<MemoryEntry> recallUser(String userId, String query, int topK) {
        return rank(userStore.loadAll(userId), query, topK);
    }

    @Override
    public List<MemoryEntry> recallPlatform(String query, int topK) {
        return rank(platformStore.loadAll(null), query, topK);
    }

    private List<MemoryEntry> rank(List<MemoryEntry> entries, String query, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            double score = similarity(query, entry.content());
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed()
                        .thenComparing(s -> s.entry.confidence(), Comparator.reverseOrder()))
                .limit(topK)
                .map(s -> s.entry)
                .toList();
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

    private record Scored(MemoryEntry entry, double score) {
    }
}
