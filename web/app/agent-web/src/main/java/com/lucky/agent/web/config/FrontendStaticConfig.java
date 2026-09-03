package com.lucky.agent.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * 前端静态资源托管 + SPA 回退。
 * <p>生产形态：后端 jar 托管 {@code web/fronted/dist}（配置 {@code lucky.web.frontend-dist}，默认
 * {@code ../fronted/dist}），浏览器访问 {@code 127.0.0.1:8080} 直达前端。
 * 路由谓词明确排除 {@code /api} 与 {@code /assets}，绝不遮蔽后端接口。</p>
 */

@Slf4j
@Configuration
public class FrontendStaticConfig {

    
    private final Path dist;
    private final Path index;

    public FrontendStaticConfig(@Value("${lucky.web.frontend-dist:../../fronted/dist}") String distPath) {
        this.dist = Path.of(distPath).toAbsolutePath().normalize();
        this.index = dist.resolve("index.html");
        log.info("前端静态目录：{}（index.html={}）", dist, Files.exists(index));
    }

    @Bean
    public RouterFunction<ServerResponse> spaRoutes() {
        return RouterFunctions
                .route(this::isAsset, req -> serveFile(req.path().substring(1)))
                .andRoute(req -> req.method() == HttpMethod.GET && req.path().equals("/favicon.svg"),
                        req -> serveFile("favicon.svg"))
                .andRoute(req -> req.method() == HttpMethod.GET
                                && !req.path().startsWith("/api")
                                && !req.path().startsWith("/assets"),
                        this::spaFallback);
    }

    /** 命中 dist 内真实文件则返回，否则回退 index.html（history 路由）。*/
    private Mono<ServerResponse> spaFallback(ServerRequest req) {
        Path file = dist.resolve(req.path().substring(1)).normalize();
        if (Files.isRegularFile(file)) {
            return serveFile(req.path().substring(1));
        }
        return serveIndex();
    }

    private boolean isAsset(ServerRequest req) {
        return req.method() == HttpMethod.GET && req.path().startsWith("/assets/");
    }

    private Mono<ServerResponse> serveFile(String relative) {
        Path file = dist.resolve(relative).normalize();
        if (!Files.isRegularFile(file)) {
            return ServerResponse.notFound().build();
        }
        try {
            MediaType media = mediaTypeFor(file.getFileName().toString());
            return ServerResponse.ok().contentType(media).bodyValue(Files.readAllBytes(file));
        } catch (Exception e) {
            log.warn("读取静态资源失败：{}", file, e);
            return ServerResponse.notFound().build();
        }
    }

    private Mono<ServerResponse> serveIndex() {
        try {
            return ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                    .bodyValue(Files.readString(index));
        } catch (Exception e) {
            log.warn("读取 index.html 失败：{}", index, e);
            return ServerResponse.notFound().build();
        }
    }

    private MediaType mediaTypeFor(String name) {
        if (name.endsWith(".js")) {
            return MediaType.valueOf("application/javascript");
        }
        if (name.endsWith(".css")) {
            return MediaType.valueOf("text/css");
        }
        if (name.endsWith(".svg")) {
            return MediaType.valueOf("image/svg+xml");
        }
        if (name.endsWith(".html")) {
            return MediaType.TEXT_HTML;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
