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
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 前端静态资源托管 + SPA 回退。
 * <p>生产形态：后端 jar 托管 {@code web/fronted/dist}（配置 {@code lucky.web.frontend-dist}），
 * 浏览器访问 {@code 127.0.0.1:8080} 直达前端。
 * 路由谓词明确排除 {@code /api} 与 {@code /assets}，绝不遮蔽后端接口。</p>
 *
 * <p><b>默认路径为何要「多候选探测」：</b>相对路径基于<b>进程工作目录</b>解析，而非 jar 位置。
 * 同一份 jar 从 {@code web/app}（Maven/脚本）启动与从仓库根（IDEA 默认）启动，相对基准不同：
 * 前者 {@code ../../fronted/dist} 命中，后者会解析到不存在的 {@code <repo>/fronted/dist}，
 * 表现为「后端能起、API 正常，但访问 8080 首页 404」——极易被误判为前端代码问题。
 * 因此这里按候选顺序探测真实存在的目录，全部落空才回退首个候选并告警。</p>
 */

@Slf4j
@Configuration
public class FrontendStaticConfig {

    
    private final Path dist;
    private final Path index;

    public FrontendStaticConfig(@Value("${lucky.web.frontend-dist:}") String configuredDist) {
        this.dist = resolveDist(configuredDist);
        this.index = dist.resolve("index.html");
        if (Files.exists(index)) {
            log.info("前端静态目录：{}（index.html=true）", dist);
        } else {
            log.warn("前端静态目录：{}（index.html=false）——访问 8080 首页将 404。"
                    + "请执行一次前端构建（web/fronted 下 npm run build），"
                    + "或用 -Dlucky.web.frontend-dist=<绝对路径> 指定 dist 目录。", dist);
        }
    }

    /**
     * 解析前端 dist 目录。
     * <p>优先级：显式配置（{@code lucky.web.frontend-dist}，绝对路径优先）→ 相对工作目录的候选 →
     * 相对 jar/classes 位置的候选。返回首个真实存在 {@code index.html} 的目录；都不可用时
     * 返回第一个候选（保持原行为，仅日志告警），避免启动失败。</p>
     */
    private static Path resolveDist(String configuredDist) {
        List<Path> candidates = new ArrayList<>();
        if (configuredDist != null && !configuredDist.isBlank()) {
            candidates.add(normalize(configuredDist));
        }
        // 相对「进程工作目录」的候选：覆盖 Maven/脚本从 web/app 或仓库根启动两种情形
        candidates.add(normalize("../../fronted/dist"));
        candidates.add(normalize("../fronted/dist"));
        candidates.add(normalize("fronted/dist"));
        candidates.add(normalize("web/fronted/dist"));
        // 相对「本类所在位置」的候选：覆盖 IDEA 直接运行 classes 的情形
        for (Path base : codeSourceBases()) {
            candidates.add(base.resolve("../../fronted/dist").normalize());
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("index.html"))) {
                return c;
            }
        }
        return candidates.get(0);
    }

    private static Path normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    /** 本类 class 文件所在目录（如 {@code web/app/agent-web/target/classes}），失败返回空列表。 */
    private static List<Path> codeSourceBases() {
        try {
            var url = FrontendStaticConfig.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) {
                return List.of();
            }
            Path p = Path.of(url.toURI());
            return List.of(Files.isDirectory(p) ? p : p.getParent());
        } catch (Exception e) {
            return List.of();
        }
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
