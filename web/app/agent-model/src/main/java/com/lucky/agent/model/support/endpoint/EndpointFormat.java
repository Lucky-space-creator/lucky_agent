package com.lucky.agent.model.support.endpoint;

import java.net.URI;

/**
 * 模型端点格式识别（判定 OpenAI / Anthropic 两种 wire 协议）。
 *
 * <p>端点 URL 一律由用户提供，且作为 LangChain4j 官方模型的 {@code baseUrl} 原样透传——
 * 接口路径（OpenAI 的 {@code /chat/completions}、Anthropic 的 {@code /messages}）由官方 SDK
 * 拼接，后端不再自行补全，避免不同厂商 baseUrl 约定差异导致 404（如 DeepSeek 的
 * {@code https://api.deepseek.com/anthropic}）。本类只负责：</p>
 * <ul>
 *     <li>{@link #fromUrl(String)}：按 URL 判断协议格式（选择适配器与鉴权头）。</li>
 *     <li>{@link #resolveUrl(String)}：校验并原样返回 baseUrl（去尾斜杠）。</li>
 *     <li>{@link #endpointPath(String)}：返回该格式的接口路径（供健康探测拼完整地址）。</li>
 * </ul>
 */
public enum EndpointFormat {

    OPENAI,
    ANTHROPIC;

    /** Anthropic 官方域名后缀（裸域名形态按 Anthropic 处理）。 */
    private static final String ANTHROPIC_HOST_SUFFIX = "anthropic.com";
    /** Anthropic 兼容 base_url 的常见路径后缀（DeepSeek 等厂商约定）。 */
    private static final String ANTHROPIC_BASE_SUFFIX = "/anthropic";
    /** OpenAI 兼容统一消息接口路径。 */
    public static final String OPENAI_API_PATH = "/chat/completions";
    /** Anthropic 统一消息接口路径（相对 baseUrl，由 LangChain4j 拼接）。 */
    public static final String ANTHROPIC_API_PATH = "/messages";

    /**
     * 依据端点 URL 判断格式。
     *
     * <p>判定顺序：路径以 {@code /messages} 结尾 → Anthropic；路径以 {@code /anthropic} 结尾
     * （厂商 base_url 约定）或主机为 Anthropic 官方域 → Anthropic；其余按 OpenAI 兼容处理。</p>
     */
    public static EndpointFormat fromUrl(String endpointUrl) {
        String path = pathOf(endpointUrl);
        if (path.endsWith("/messages")) {
            return ANTHROPIC;
        }
        if (path.endsWith(ANTHROPIC_BASE_SUFFIX) || hostOf(endpointUrl).endsWith(ANTHROPIC_HOST_SUFFIX)) {
            return ANTHROPIC;
        }
        return OPENAI;
    }

    /**
     * 校验并原样返回用户填写的 baseUrl（去尾斜杠），不做路径补全。
     *
     * <p>作为 LangChain4j 官方模型的 {@code baseUrl}：OpenAI 填 {@code https://api.deepseek.com}
     * 或 {@code https://.../v1}；Anthropic 填 {@code https://api.deepseek.com/anthropic}。
     * 接口路径由官方 SDK 拼接。</p>
     *
     * @param endpointUrl 用户填写的 base_url
     * @return 原样透传（去尾斜杠后）的地址；入参为空时原样返回
     * @throws IllegalArgumentException URL 非法（非 http/https 或无主机）
     */
    public static String resolveUrl(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return endpointUrl;
        }
        String base = trimSlash(endpointUrl.trim());
        if (schemeOf(base) == null || hostOf(base).isBlank()) {
            throw new IllegalArgumentException("端点地址格式不正确，需以 http(s):// 开头并包含主机");
        }
        return base;
    }

    /**
     * 返回该格式的消息接口路径（健康探测拼完整地址用）。
     */
    public static String endpointPath(String endpointUrl) {
        return fromUrl(endpointUrl) == ANTHROPIC ? ANTHROPIC_API_PATH : OPENAI_API_PATH;
    }

    /** 去掉结尾斜杠（保留根路径 {@code /} 场景下的原始语义）。 */
    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 提取 URL 路径（小写、去尾斜杠）；解析失败或无路径返回空串。 */
    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return "";
            }
            String trimmed = trimSlash(path);
            return trimmed.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 提取 URL 主机（小写）；解析失败或无主机返回空串。 */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 提取 URL 协议（小写）；仅 http/https 视为合法，其余（含无协议）返回 null。 */
    private static String schemeOf(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            if (scheme == null) {
                return null;
            }
            String s = scheme.toLowerCase();
            return "http".equals(s) || "https".equals(s) ? s : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
