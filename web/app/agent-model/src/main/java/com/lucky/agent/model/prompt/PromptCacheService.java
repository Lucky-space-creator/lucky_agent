package com.lucky.agent.model.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提示词缓存服务（D17 Phase2：cache_control / inherit）。
 *
 * <p>基于 {@link SystemPromptAssembler} 的静态/动态分界，识别可缓存静态前缀并计算指纹；
 * 暴露覆盖率与继承标记，供模型适配层在供应商支持时设置 {@code cache_control} 断点。
 * 子代理传 {@code inherit=true} 时复用父代理静态前缀指纹，避免重复前缀计费。
 * 内部以原子计数器与指纹集合统计命中/未命中，供状态面板查询（见 {@link #status()}）。</p>
 */
public class PromptCacheService {

    /** 指纹集合上限，防止长时间运行内存膨胀；超出后重建以保持统计近似。 */
    private static final int MAX_DISTINCT_PREFIXES = 1024;

    private final AtomicLong totalPlans = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong inheritPlans = new AtomicLong();
    private final AtomicLong totalStaticChars = new AtomicLong();
    private final AtomicLong totalChars = new AtomicLong();
    private final Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
    private volatile String lastFingerprint = "";
    private volatile int lastFullLength;

    /** 计算静态前缀指纹（SHA-256）。 */
    public String fingerprint(String staticPrefix) {
        if (staticPrefix == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(staticPrefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 生成缓存计划。
     *
     * @param assembler 已组装的提示词组装器（含静态/动态分界）
     * @param inherit    子代理是否继承父代理缓存前缀
     * @return 缓存计划
     */
    public PromptCachePlan plan(SystemPromptAssembler assembler, boolean inherit) {
        return recordAndBuild(assembler.staticPrefix(), assembler.assemble(), inherit);
    }

    /**
     * 生成缓存计划（直接传入已组装的完整提示词与静态前缀）。
     */
    public PromptCachePlan plan(String staticPrefix, String fullPrompt, boolean inherit) {
        return recordAndBuild(staticPrefix, fullPrompt, inherit);
    }

    /**
     * 判断两个静态前缀指纹是否一致（用于命中判定/失效检测）。
     */
    public boolean samePrefix(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    /**
     * 缓存运行明细（累计统计）。
     */
    public PromptCacheStatus status() {
        double coverage = totalChars.get() == 0 ? 0D
                : (double) totalStaticChars.get() / totalChars.get();
        return new PromptCacheStatus(totalPlans.get(), hits.get(), misses.get(),
                seenFingerprints.size(), inheritPlans.get(), coverage,
                lastFingerprint, lastFullLength);
    }

    private PromptCachePlan recordAndBuild(String staticPrefix, String fullPrompt, boolean inherit) {
        totalPlans.incrementAndGet();
        if (inherit) {
            inheritPlans.incrementAndGet();
        }
        String fp = fingerprint(staticPrefix);
        if (fp.isEmpty() || seenFingerprints.add(fp)) {
            misses.incrementAndGet();
        } else {
            hits.incrementAndGet();
        }
        if (seenFingerprints.size() > MAX_DISTINCT_PREFIXES) {
            seenFingerprints.clear();
            seenFingerprints.add(fp);
        }
        int staticLen = staticPrefix == null ? 0 : staticPrefix.length();
        int fullLen = fullPrompt == null ? 0 : fullPrompt.length();
        totalStaticChars.addAndGet(staticLen);
        totalChars.addAndGet(fullLen);
        lastFingerprint = fp;
        lastFullLength = fullLen;
        return new PromptCachePlan(staticPrefix, fp, staticLen, fullLen, inherit);
    }
}
