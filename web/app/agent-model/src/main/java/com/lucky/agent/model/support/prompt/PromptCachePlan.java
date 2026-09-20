package com.lucky.agent.model.support.prompt;

import java.util.List;

/**
 * 提示词缓存计划（D17 Phase2：cache_control / inherit）。
 *
 * <p>描述一次请求中「可缓存静态前缀」的边界与指纹，供模型适配层在支持 cache_control 时
 * 设置断点；子代理通过 {@link #inherit()} 复用父代理的缓存前缀指纹，避免重复计费。</p>
 */
public class PromptCachePlan {

    /** 可缓存静态前缀文本（字节级稳定）。 */
    private final String cacheablePrefix;
    /** 静态前缀指纹（SHA-256 十六进制），变化即缓存失效。 */
    private final String prefixFingerprint;
    /** 静态前缀字符数。 */
    private final int staticLength;
    /** 本次完整提示词字符数。 */
    private final int totalLength;
    /** 缓存覆盖率（静态 / 总）。 */
    private final double coverage;
    /** 子代理是否继承父代理缓存前缀。 */
    private final boolean inherit;
    /** 命中标记（由适配层在返回时回填，用于指标）。 */
    private boolean hit;

    public PromptCachePlan(String cacheablePrefix, String prefixFingerprint,
                           int staticLength, int totalLength, boolean inherit) {
        this.cacheablePrefix = cacheablePrefix;
        this.prefixFingerprint = prefixFingerprint;
        this.staticLength = staticLength;
        this.totalLength = totalLength;
        this.inherit = inherit;
        this.coverage = totalLength <= 0 ? 0d : (double) staticLength / totalLength;
    }

    public String cacheablePrefix() {
        return cacheablePrefix;
    }

    public String prefixFingerprint() {
        return prefixFingerprint;
    }

    public int staticLength() {
        return staticLength;
    }

    public int totalLength() {
        return totalLength;
    }

    public double coverage() {
        return coverage;
    }

    public boolean inherit() {
        return inherit;
    }

    public boolean hit() {
        return hit;
    }

    public void hit(boolean hit) {
        this.hit = hit;
    }

    /** 覆盖率为可缓存前缀占比；>0.5 视为「静态主导」，适合开启 cache_control。 */
    public boolean cacheable() {
        return coverage > 0.2 && staticLength > 0;
    }
}
