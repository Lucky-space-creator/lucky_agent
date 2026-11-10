package com.lucky.agent.web.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态资源路径边界测试（台账 #2）。
 *
 * <p>回归背景：{@code dist.resolve(relative).normalize()} 之后没有回判
 * {@code startsWith(dist)}，{@code GET /..%2f..%2fconfig.json} 可把工作空间外的
 * 任意文件读出来（会话 JSONL、模型密钥配置都在同机）。</p>
 *
 * <p>为什么直接测 {@link FrontendStaticConfig#resolveInDist}：{@code WebTestClient}
 * 在客户端侧就会规范化 URI，{@code ../} 根本到不了路由函数，用它测会得到
 * 「假绿」——测的是 Spring 的解析器，不是我们的边界。</p>
 */
class FrontendStaticConfigTest {

    @Test
    void shouldServeFilesInsideDist(@TempDir Path dist) throws Exception {
        Files.writeString(dist.resolve("index.html"), "<html></html>");
        Files.createDirectories(dist.resolve("assets"));
        Files.writeString(dist.resolve("assets/app.js"), "console.log(1)");

        FrontendStaticConfig config = new FrontendStaticConfig(dist.toString());

        assertThat(config.resolveInDist("assets/app.js")).isNotNull();
        assertThat(config.resolveInDist("index.html")).isNotNull();
    }

    @Test
    void shouldRejectTraversalOutsideDist(@TempDir Path base) throws Exception {
        Path dist = Files.createDirectories(base.resolve("fronted/dist"));
        Files.writeString(dist.resolve("index.html"), "<html></html>");
        Files.writeString(base.resolve("secret.json"), "{\"apiKey\":\"leaked\"}");

        FrontendStaticConfig config = new FrontendStaticConfig(dist.toString());

        // 相对穿越：回到 base 读 secret.json
        assertThat(config.resolveInDist("../secret.json")).isNull();
        assertThat(config.resolveInDist("../../../../../../etc/passwd")).isNull();
        assertThat(config.resolveInDist("assets/../../secret.json")).isNull();

        // 绝对路径（Windows 盘符 / POSIX 根）同样不得穿透
        assertThat(config.resolveInDist(base.resolve("secret.json").toString())).isNull();
        assertThat(config.resolveInDist("/etc/passwd")).isNull();

        // dist 自身仍可访问（空相对路径视为非法，避免把目录当文件读）
        assertThat(config.resolveInDist("")).isNull();
        assertThat(config.resolveInDist(null)).isNull();
    }
}
