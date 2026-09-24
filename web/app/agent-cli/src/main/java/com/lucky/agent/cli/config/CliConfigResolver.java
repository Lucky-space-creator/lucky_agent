package com.lucky.agent.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CLI 配置解析：三级优先级 + 键白名单。
 *
 * <p><b>优先级（高 → 低）</b></p>
 * <ol>
 *   <li>命令行参数</li>
 *   <li>环境变量 {@code LUCKY_*}（如 {@code LUCKY_MODEL} / {@code LUCKY_WORKSPACE}）</li>
 *   <li>项目本地 {@code <cwd>/.lucky/settings.json}</li>
 *   <li>内置默认值（由内核 {@code CoreProperties} 与模型路由承担）</li>
 * </ol>
 *
 * <p><b>为什么是三级而不是五级</b>：主流 CLI 的多层配置里，多出来的层通常是「系统托管层」与
 * 「企业策略层」。本项目是本地单用户、零托管（决策 D1/D3），这两层不存在，硬加只会让
 * 「为什么这个值没生效」变得难查。用户级配置仍由既有 {@code ~/.lucky_agent/settings.json}
 * （模型与推理深度）承担，CLI 不重复实现。</p>
 *
 * <p><b>安全红线（本类最重要的约束）</b>：项目本地配置是<b>可能被提交进代码库</b>的文件。
 * 因此加载时做键白名单过滤——只接受无安全含义的运行参数；一旦发现禁止键，
 * <b>整个文件拒绝加载并非静默忽略</b>，否则用户会以为配置生效了。禁止键包括任何密钥材料、
 * 权限级别与规则、MCP 端点与授权、路径根。</p>
 */
@Slf4j
public final class CliConfigResolver {

    /** 项目本地配置的相对路径。 */
    public static final String PROJECT_CONFIG_RELATIVE = ".lucky/settings.json";

    /** 环境变量前缀。 */
    public static final String ENV_PREFIX = "LUCKY_";

    /** 允许出现在项目本地配置中的键（无安全含义的运行参数）。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "model", "outputFormat", "plain", "maxTurns", "maxBudget", "workspace");

    /**
     * 禁止出现在项目本地配置中的键。
     *
     * <p>逐项理由：{@code apiKey}/{@code models} 会把密钥带进代码库（密钥只在用户级
     * {@code settings.json} 且 AES-GCM 加密落盘，决策 D8）；{@code permissionRules}/{@code permissionLevel}
     * 会让「clone 一个仓库即可提权」，绕过执行臂硬边界（决策 D2）；{@code mcp} 会改写外部工具授权；
     * {@code workspaceRoot}/{@code frameworkRoot} 会改写路径根，使执行臂边界随仓库漂移。</p>
     *
     * <p><b>匹配一律大小写不敏感</b>：本集合只是「人类可读的目录」，真正的判定走
     * {@link #FORBIDDEN_KEYS_LC}。曾经的写法是拿 {@code key.toLowerCase()} 去查本集合，
     * 而本集合里含大写字母的成员（{@code permissionLevel}、{@code mcpServers}…）永远匹配不上
     * —— 即 {@code {"permissionLevel":"FULL"}} 会被静默放行，直接击穿 D2 边界。这类
     * 「目录与索引不一致」的坑必须靠单一索引消除，不能靠逐成员加小写别名。</p>
     */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "apiKey", "apikey", "key", "token", "secret",
            "models", "permissionRules", "permissionLevel", "permission",
            "mcp", "mcpServers", "mcpAuth",
            "workspaceRoot", "frameworkRoot", "root");

    /** {@link #FORBIDDEN_KEYS} 的小写索引（唯一判定入口）。 */
    private static final Set<String> FORBIDDEN_KEYS_LC = lower(FORBIDDEN_KEYS);

    /** 允许键的「小写 → 规范名」索引：用户写 {@code outputformat} 也能生效，落库仍是规范名。 */
    private static final Map<String, String> ALLOWED_BY_LC = allowedByLowerCase();

    private static Set<String> lower(Set<String> src) {
        Set<String> out = new HashSet<>(src.size() * 2);
        for (String s : src) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static Map<String, String> allowedByLowerCase() {
        Map<String, String> out = new HashMap<>();
        for (String s : ALLOWED_KEYS) {
            out.put(s.toLowerCase(Locale.ROOT), s);
        }
        return Map.copyOf(out);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 项目本地配置的加载结果。
     *
     * @param source       配置文件路径（未启用时为 null）
     * @param values       白名单内的键值
     * @param rejectedKeys 命中的禁止键（非空表示文件被整体拒绝）
     * @param enabled      是否成功加载
     */
    public record ProjectConfig(Path source, Map<String, String> values,
                                List<String> rejectedKeys, boolean enabled) {

        static ProjectConfig disabled() {
            return new ProjectConfig(null, Map.of(), List.of(), false);
        }
    }

    /**
     * 从起始目录向上查找并加载项目本地配置。
     *
     * <p>若起始目录位于框架根内（例如直接从 {@code ~/.lucky_agent/workspace} 启动），
     * 则跳过项目本地层——避免框架读到自己内部的数据目录。</p>
     *
     * @param startDir     起始目录（一般是进程 CWD）
     * @param frameworkRoot 框架根（{@code ~/.lucky_agent}）
     */
    public ProjectConfig loadProjectConfig(Path startDir, Path frameworkRoot) {
        if (startDir == null) {
            return ProjectConfig.disabled();
        }
        Path dir = startDir.toAbsolutePath().normalize();
        if (frameworkRoot != null && dir.startsWith(frameworkRoot.toAbsolutePath().normalize())) {
            log.debug("起始目录位于框架根内，跳过项目本地配置层：{}", dir);
            return ProjectConfig.disabled();
        }
        Path file = dir.resolve(PROJECT_CONFIG_RELATIVE);
        if (!Files.isRegularFile(file)) {
            return ProjectConfig.disabled();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(Files.readString(file), Map.class);
            Map<String, String> values = new LinkedHashMap<>();
            List<String> rejected = new ArrayList<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String key = e.getKey();
                if (key == null) {
                    continue;
                }
                String lc = key.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEYS_LC.contains(lc)) {
                    rejected.add(key);
                } else {
                    String canonical = ALLOWED_BY_LC.get(lc);
                    if (canonical != null) {
                        values.put(canonical, e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                }
                // 其余未知键：静默忽略（不是安全问题，且允许用户为其它工具保留同文件）
            }
            if (!rejected.isEmpty()) {
                return new ProjectConfig(file, Map.of(), List.copyOf(rejected), false);
            }
            return new ProjectConfig(file, Map.copyOf(values), List.of(), true);
        } catch (Exception e) {
            log.warn("项目本地配置解析失败，已跳过：{} err={}", file, e.getMessage());
            return new ProjectConfig(file, Map.of(), List.of(), false);
        }
    }

    /** 读取环境变量覆盖（{@code LUCKY_MODEL} ← {@code model}）。 */
    public Optional<String> env(String key) {
        String name = ENV_PREFIX + key.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        String v = System.getenv(name);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }

    /**
     * 按优先级取值：命令行 &gt; 环境变量 &gt; 项目本地。
     *
     * @param flagValue    命令行取值（null/空白视为未提供）
     * @param key          配置键（同时用于推导环境变量名与项目配置键）
     * @param project      项目本地配置
     */
    public Optional<String> resolve(String flagValue, String key, ProjectConfig project) {
        if (flagValue != null && !flagValue.isBlank()) {
            return Optional.of(flagValue.trim());
        }
        Optional<String> fromEnv = env(key);
        if (fromEnv.isPresent()) {
            return fromEnv;
        }
        String fromProject = project.values().get(key);
        return fromProject == null || fromProject.isBlank() ? Optional.empty() : Optional.of(fromProject);
    }

    /** 取值来源说明（供 {@code /doctor} 与启动回显「这个值从哪来」）。 */
    public String describeSource(String flagValue, String key, ProjectConfig project) {
        if (flagValue != null && !flagValue.isBlank()) {
            return "命令行";
        }
        if (env(key).isPresent()) {
            return "环境变量 " + ENV_PREFIX + key.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        }
        if (project.values().containsKey(key)) {
            return "项目配置 " + project.source();
        }
        return "默认值";
    }
}
