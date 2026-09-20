package com.lucky.agent.core.util.hook;

import com.lucky.agent.common.dto.HookEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Webhook 外部 Hook：将事件 JSON POST/GET 到外部地址，解析响应中的裁决 JSON。
 * <p>连接与读取均受 {@code timeoutSec} 约束，超时/网络异常一律按放行处理。</p>
 */
@Slf4j
public class WebhookHook extends ExternalHook {

    private final HttpClient httpClient;

    public WebhookHook(ExternalHookConfig config) {
        super(config);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSec()))
                .build();
    }

    @Override
    protected HookEvent invoke(HookEvent event) {
        String url = config.url();
        if (url == null || url.isBlank()) {
            log.warn("Webhook Hook 未配置 url：{}", config.name());
            return event;
        }
        try {
            String eventJson = OBJECT_MAPPER.writeValueAsString(event);
            String method = config.method() == null || config.method().isBlank()
                    ? "POST" : config.method().toUpperCase();

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.timeoutSec()));
            if (config.headers() != null) {
                config.headers().forEach(builder::header);
            }
            HttpRequest request;
            if ("GET".equals(method)) {
                request = builder.GET().build();
            } else {
                request = builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(eventJson))
                        .build();
            }
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return applyDecision(event, response.body());
        } catch (IOException e) {
            log.warn("Webhook Hook 网络异常（按放行处理）：{}", config.name(), e);
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Webhook Hook 被中断（按放行处理）：{}", config.name(), e);
            return event;
        }
    }
}
