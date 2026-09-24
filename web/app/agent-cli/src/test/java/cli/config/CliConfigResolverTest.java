package cli.config;

import com.lucky.agent.cli.config.CliConfigResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目本地配置：三级优先级 + 键白名单。
 *
 * <p>安全红线在 {@link #forbiddenKeyRejectsWholeFile()}：项目本地配置是<b>可能被提交进仓库</b>的文件，
 * 一旦允许写 API Key 或权限规则，就变成「clone 一个仓库即可提权/偷密钥」。
 * 且必须<b>整体拒绝</b>而不是静默忽略某个键 —— 静默忽略会让用户误以为配置生效了。</p>
 */
class CliConfigResolverTest {

    private final CliConfigResolver resolver = new CliConfigResolver();

    @Test
    @DisplayName("白名单键正常加载")
    void allowedKeysLoaded(@TempDir Path dir) throws IOException {
        write(dir, "{\"model\":\"deepseek-chat\",\"maxTurns\":12,\"plain\":true}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));

        assertTrue(c.enabled());
        assertEquals("deepseek-chat", c.values().get("model"));
        assertEquals("12", c.values().get("maxTurns"));
        assertTrue(c.rejectedKeys().isEmpty());
    }

    @Test
    @DisplayName("出现任一禁止键 → 整个文件被拒绝加载")
    void forbiddenKeyRejectsWholeFile(@TempDir Path dir) throws IOException {
        write(dir, "{\"model\":\"x\",\"apiKey\":\"sk-secret\",\"permissionLevel\":\"FULL\"}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));

        assertFalse(c.enabled(), "含禁止键必须整体拒绝");
        assertEquals(2, c.rejectedKeys().size());
        assertTrue(c.rejectedKeys().contains("apiKey"));
        assertTrue(c.rejectedKeys().contains("permissionLevel"));
        assertTrue(c.values().isEmpty(), "被拒绝时不得留下任何生效键");
    }

    @Test
    @DisplayName("禁止键大小写不敏感（ApiKey / APIKEY 同样拦截）")
    void forbiddenKeyCaseInsensitive(@TempDir Path dir) throws IOException {
        write(dir, "{\"ApiKey\":\"sk-1\"}");
        assertFalse(resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw")).enabled());
    }

    @Test
    @DisplayName("未知键静默忽略（允许用户在同一个文件里放别的工具的配置）")
    void unknownKeysIgnored(@TempDir Path dir) throws IOException {
        write(dir, "{\"model\":\"m\",\"somethingElse\":\"v\"}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
        assertTrue(c.enabled());
        assertEquals(1, c.values().size());
    }

    @Test
    @DisplayName("起始目录位于框架根内 → 跳过项目本地层（不读自己的内部数据目录）")
    void skipsInsideFrameworkRoot(@TempDir Path dir) throws IOException {
        write(dir, "{\"model\":\"inside\"}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, dir);
        assertFalse(c.enabled());
        assertTrue(c.values().isEmpty());
    }

    @Test
    @DisplayName("没有配置文件时正常返回未启用（不是错误）")
    void noConfigFile(@TempDir Path dir) {
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
        assertFalse(c.enabled());
        assertTrue(c.rejectedKeys().isEmpty());
    }

    @Test
    @DisplayName("配置文件内容非法 JSON → 跳过并保持未启用，不抛异常")
    void malformedJsonSkipped(@TempDir Path dir) throws IOException {
        write(dir, "{ not json");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
        assertFalse(c.enabled());
    }

    @Test
    @DisplayName("优先级：命令行 > 环境变量 > 项目配置")
    void priorityOrder(@TempDir Path dir) throws IOException {
        write(dir, "{\"model\":\"from-project\"}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
        // 环境变量名推导：maxTurns → LUCKY_MAX_TURNS
        assertEquals(CliConfigResolver.ENV_PREFIX + "MAX_TURNS", envName("maxTurns"));
        assertEquals("from-project", resolver.resolve(null, "model", c).orElseThrow());
        assertEquals("from-flag", resolver.resolve("from-flag", "model", c).orElseThrow());
        assertEquals("命令行", resolver.describeSource("from-flag", "model", c));
        assertEquals("项目配置 " + c.source(), resolver.describeSource(null, "model", c));
    }

    @Test
    @DisplayName("无任何来源时由内核默认值兜底（resolve 返回空）")
    void fallsBackToDefaults(@TempDir Path dir) {
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
        assertTrue(resolver.resolve(null, "maxTurns", c).isEmpty());
        assertEquals("默认值", resolver.describeSource(null, "maxTurns", c));
    }

    @Test
    @DisplayName("FORBIDDEN_KEYS 每一项都必须真的能被命中（防「目录与索引不一致」）")
    void everyForbiddenKeyIsEnforced(@TempDir Path dir) throws IOException {
        // 这不是重复用例：曾经的缺陷正是「集合里有 permissionLevel，但查找用小写 key」，
        // 导致含大写的成员永远匹配不上、静默放行。逐项遍历才是这类缺陷的唯一有效守卫。
        for (String key : CliConfigResolver.FORBIDDEN_KEYS) {
            write(dir, "{\"" + key + "\":\"x\"}");
            CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));
            assertFalse(c.enabled(), "禁止键未被拦截：" + key);
            assertTrue(c.rejectedKeys().contains(key), "未记录命中键：" + key);
        }
    }

    @Test
    @DisplayName("允许键大小写不敏感，落库统一为规范名")
    void allowedKeysAreCaseInsensitive(@TempDir Path dir) throws IOException {
        write(dir, "{\"outputformat\":\"json\",\"MAXTURNS\":\"5\"}");
        CliConfigResolver.ProjectConfig c = resolver.loadProjectConfig(dir, Path.of("/nonexistent-fw"));

        assertTrue(c.enabled());
        assertEquals("json", c.values().get("outputFormat"));
        assertEquals("5", c.values().get("maxTurns"));
    }

    private static String envName(String key) {
        return CliConfigResolver.ENV_PREFIX
                + key.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private static void write(Path dir, String json) throws IOException {
        Path f = dir.resolve(CliConfigResolver.PROJECT_CONFIG_RELATIVE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
    }
}
