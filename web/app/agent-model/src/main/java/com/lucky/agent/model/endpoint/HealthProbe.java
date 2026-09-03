package com.lucky.agent.model.endpoint;

import com.lucky.agent.model.api.dto.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端点健康探测（fallback 与健康探测）。
 *
 * <p>对 {@code GET {规整后 endpointUrl}} 探测可达性（2xx/3xx 与 405 视为可达，
 * 401/403 视为鉴权失败，404 视为路径错误，网络异常视为不可达）；主端点探活失败自动切备用端点。
 * 探测返回结构化结果，供设置界面「测试连接」与发送前校验展示。</p>
 *
 * <p>探测必须使用与真实调用完全一致的 URL 与鉴权头（{@link EndpointFormat#resolveUrl(String)} +
 * OpenAI 走 {@code Authorization}、Anthropic 走 {@code x-api-key}），否则探活结论不可信。</p>
 *
 * <p>探测使用 JDK 阻塞 {@link HttpClient} 而非 WebClient：本应用为 WebFlux（Reactor Netty），
 * 同步控制器运行在事件循环线程，WebClient 的 {@code block()} 在该线程被 Reactor 禁止；JDK
 * HttpClient 与线程模型无关，异常可归类为面向用户的友好提示。</p>
 */
@Slf4j
@Component
public class HealthProbe {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 与 {@link AnthropicCompatibleModel} 保持一致的协议版本头。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final Map<String, Boolean> health = new ConcurrentHashMap<>();

    public HealthProbe() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 端点健康状态（未探活过默认视为健康）。 */
    public boolean isHealthy(String endpointId) {
        return health.getOrDefault(endpointId, true);
    }

    /** 探活单个端点并更新健康缓存。 */
    public void probe(ModelConfig config) {
        probeResult(config);
    }

    /** 探活单个端点，返回结构化结果并更新健康缓存。 */
    public ProbeResult probeResult(ModelConfig config) {
        ProbeResult result = doProbe(config);
        if (result.id() != null) {
            health.put(result.id(), result.healthy());
        }
        log.info("端点探活：{} health={}", result.name(), result.healthy());
        return result;
    }

    /** 探测单个端点（不写健康缓存，用于测试未保存的表单配置）。 */
    public ProbeResult probeOnce(ModelConfig config) {
        return doProbe(config);
    }

    private ProbeResult doProbe(ModelConfig config) {
        String id = config == null ? null : config.id();
        String name = config == null ? "" : config.name();
        if (config == null || config.endpointUrl() == null || config.endpointUrl().isBlank()) {
            return new ProbeResult(id, name, false, null, "端点 URL 未配置");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            return new ProbeResult(id, name, false, null, "API Key 未配置");
        }
        // 探活与真实调用必须命中同一地址、同一鉴权头，否则「测试连接」结果不可信。
        // 与 LangChain4j 官方模型一致：baseUrl + 该格式的接口路径（/chat/completions 或 /messages）
        try {
            String base = EndpointFormat.resolveUrl(config.endpointUrl());
            boolean anthropic = EndpointFormat.fromUrl(base) == EndpointFormat.ANTHROPIC;
            URI uri = URI.create(base + EndpointFormat.endpointPath(base));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(PROBE_TIMEOUT)
                    .GET();
            if (anthropic) {
                builder.header("x-api-key", config.apiKey());
                builder.header("anthropic-version", ANTHROPIC_VERSION);
            } else {
                builder.header("Authorization", "Bearer " + config.apiKey());
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                return new ProbeResult(id, name, true, status, "可达（HTTP " + status + "）");
            }
            return switch (status) {
                case 401, 403 -> new ProbeResult(id, name, false, status, "鉴权失败（HTTP " + status + "），请检查 API Key");
                case 404 -> new ProbeResult(id, name, false, status, "端点路径不存在（HTTP 404），请检查 URL");
                // 消息接口只接受 POST：GET 返回 405 恰恰说明地址可达
                case 405 -> new ProbeResult(id, name, true, status, "可达（HTTP 405，接口仅接受 POST）");
                default -> status < 500
                        ? new ProbeResult(id, name, true, status, "可达（HTTP " + status + "）")
                        : new ProbeResult(id, name, false, status, "服务异常（HTTP " + status + "）");
            };
        } catch (Exception e) {
            log.debug("端点不可达：{}", config.name(), e);
            return new ProbeResult(id, name, false, null, friendlyError(e));
        }
    }

    /** 将异常归类为面向用户的友好提示，不外泄内部堆栈/技术细节。 */
    private String friendlyError(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return "端点地址格式不正确，请检查 URL";
        }
        if (e instanceof HttpTimeoutException || e instanceof HttpConnectTimeoutException) {
            return "连接超时，请检查网络或端点地址";
        }
        if (e instanceof UnknownHostException) {
            return "无法解析域名，请检查 URL 是否正确";
        }
        if (e instanceof ConnectException) {
            return "无法连接服务器，请检查地址与网络";
        }
        if (e instanceof SSLException) {
            return "HTTPS 证书校验失败，请确认端点地址正确";
        }
        return "连接失败，请检查端点地址与网络后重试";
    }

    /** 探活结果：healthy 表示可达（2xx-4xx），status 为响应码，message 为面向用户的说明。 */
    public record ProbeResult(String id, String name, boolean healthy, Integer status, String message) {
    }
}
