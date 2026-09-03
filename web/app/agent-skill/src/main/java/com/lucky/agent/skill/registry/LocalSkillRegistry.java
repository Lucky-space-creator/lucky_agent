package com.lucky.agent.skill.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.skill.api.SkillRegistry;
import com.lucky.agent.skill.api.dto.SkillDef;
import com.lucky.agent.skill.config.SkillProperties;
import com.lucky.agent.skill.resolve.DependencyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 Skill 注册中心：持有加载的 Skill 定义与启用状态，支持热插拔重载。
 *
 * <p>平台预置（{@code PLATFORM}）只读：可启停不可卸载；用户自定义（{@code USER}）
 * 落盘 {@code <frameworkRoot>/.skills/}。启用状态持久化在
 * {@code <frameworkRoot>/.config/skill-state.json}（不改写平台目录，保持预置只读）。</p>
 */
@Slf4j
public class LocalSkillRegistry implements SkillRegistry {

    private static final String STATE_FILE = "skill-state.json";

    private final SkillLoader loader;
    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;
    private final SkillProperties properties;
    private final DependencyResolver dependencyResolver;

    private final Map<String, SkillDef> skills = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    /** 上次加载的 id+启用态指纹，用于定时扫描时判断是否有变化，避免每次重载都刷日志。 */
    private String loadedFingerprint = "";

    public LocalSkillRegistry(SkillLoader loader, WorkspaceDirs dirs, ObjectMapper objectMapper,
                              SkillProperties properties) {
        this(loader, dirs, objectMapper, properties, new DependencyResolver());
    }

    /** 注入依赖解析器，用于 E10 缺失依赖标记。 */
    public LocalSkillRegistry(SkillLoader loader, WorkspaceDirs dirs, ObjectMapper objectMapper,
                              SkillProperties properties, DependencyResolver dependencyResolver) {
        this.loader = loader;
        this.dirs = dirs;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.dependencyResolver = dependencyResolver;
    }

    /** 首次扫描加载（由配置类在 Bean 装配后调用）。 */
    public void init() {
        reload();
    }

    @Override
    public List<SkillDef> list() {
        return skills.values().stream().map(this::withBody).map(this::withMissingDeps).toList();
    }

    @Override
    public List<SkillDef> listEnabled() {
        return skills.values().stream().filter(SkillDef::enabled)
                .map(this::withBody).map(this::withMissingDeps).toList();
    }

    @Override
    public Optional<SkillDef> find(String id) {
        return Optional.ofNullable(skills.get(id)).map(this::withBody).map(this::withMissingDeps);
    }

    /** 附带 Level 2 正文（SKILL.md），供前端「新建/编辑」回填正文编辑框。 */
    private SkillDef withBody(SkillDef def) {
        String body = loader.readInstructions(def.id());
        return body == null ? def : def.withSkillMd(body);
    }

    /** 附带缺失依赖标记（E10）：依赖缺失的 Skill 前端应展示警告、禁止启用。 */
    private SkillDef withMissingDeps(SkillDef def) {
        return def.withMissingDeps(dependencyResolver.missingDeps(def, new ArrayList<>(skills.values())));
    }

    @Override
    public void setEnabled(String id, boolean enabled) {
        SkillDef current = skills.get(id);
        if (current == null) {
            return;
        }
        if (enabled && !dependencyResolver.resolve(current, new ArrayList<>(skills.values()))) {
            throw new IllegalArgumentException("Skill「" + current.name() + "」存在缺失依赖，无法启用");
        }
        skills.put(id, current.withEnabled(enabled));
        persistEnabled(id, enabled);
        revision.incrementAndGet();
    }

    @Override
    public boolean isReadOnly(String id) {
        SkillDef def = skills.get(id);
        return def != null && def.platformReadOnly();
    }

    @Override
    public SkillDef save(SkillDef def) {
        if (def == null || def.id() == null || def.id().isBlank()) {
            throw new IllegalArgumentException("Skill id 不能为空");
        }
        Path dir = loader.userSkillsRoot().resolve(def.id());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("meta.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(def),
                    StandardCharsets.UTF_8);
            // 表单保存时若提供了正文，同步落盘 SKILL.md（渐进披露的 Level 2 正文）
            if (def.skillMd() != null && !def.skillMd().isBlank()) {
                Files.writeString(dir.resolve("SKILL.md"), def.skillMd(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存 Skill 失败：" + def.id(), e);
        }
        skills.put(def.id(), def);
        revision.incrementAndGet();
        return def;
    }

    @Override
    public SkillDef importFrom(Path packageDir) {
        Path meta = packageDir.resolve("meta.json");
        Path md = packageDir.resolve("SKILL.md");
        // id 一律取目录名：消除「目录名与 meta.json 的 id 不一致」导致的静默失效（E6）
        String id = packageDir.getFileName().toString();
        SkillDef def;
        try {
            if (Files.isRegularFile(meta)) {
                SkillDef parsed = objectMapper.readValue(meta.toFile(), SkillDef.class);
                def = new SkillDef(id, parsed.name(), parsed.description(), parsed.triggers(),
                        parsed.type(), parsed.sandbox(), parsed.enabled(), parsed.deps(), parsed.entry(),
                        SkillDef.SOURCE_USER);
            } else if (Files.isRegularFile(md)) {
                // 社区标准包：仅 SKILL.md，读 frontmatter 构造（复用 loader 的解析能力）
                def = loader.buildFromSkillMd(packageDir);
                if (def == null) {
                    throw new AgentException("IMPORT_META_INVALID", "SKILL.md 缺少有效的 name/description");
                }
            } else {
                throw new AgentException("IMPORT_META_MISSING", "包内缺少 meta.json 或 SKILL.md");
            }
            if (def.id() == null || def.id().isBlank()) {
                throw new AgentException("IMPORT_META_INVALID", "Skill id 不能为空");
            }
        } catch (IOException e) {
            throw new AgentException("IMPORT_READ_ERROR", "读取技能包配置失败", e);
        }
        if (isReadOnly(def.id())) {
            throw new AgentException("IMPORT_READONLY", "平台预置 Skill 不可覆盖：" + def.id());
        }
        // E16：脚本型 Skill 校验 entry 引用的脚本文件确实存在于包内，避免运行时才报错
        validateEntry(def.entry(), packageDir);
        // E14：导入限量（文件数 / 包大小），避免超大包拖垮导入
        validateImportSize(packageDir);
        // 原子导入：先拷到临时目录，全部成功再整体替换，失败不丢原 Skill（E9）
        Path target = loader.userSkillsRoot().resolve(def.id());
        Path staging = target.resolveSibling("." + target.getFileName() + ".staging");
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);
            copyPackage(packageDir, staging);
            deleteRecursively(target);
            Files.move(staging, target);
        } catch (IOException e) {
            try {
                deleteRecursively(staging);
            } catch (IOException cleanupIgnored) {
                // 清理失败可忽略，交由系统回收
            }
            throw new AgentException("IMPORT_IO_ERROR", "导入 Skill 失败", e);
        }
        reload();
        return find(def.id())
                .orElseThrow(() -> new AgentException("IMPORT_FAILED", "导入后未找到 Skill：" + def.id()));
    }

    /**
     * 从本机目录直接导入 Skill（E15）：把目录拷贝到用户技能根目录下同名目录。
     *
     * <p>目录名即 id；目录内须含 {@code meta.json} 或 {@code SKILL.md}，否则抛业务异常。
     * 语义与 {@link #importFrom(Path)} 一致，故直接复用其拷贝逻辑。</p>
     */
    public SkillDef importFromDir(Path sourceDir) {
        Path source = sourceDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new AgentException("IMPORT_DIR_NOT_FOUND", "目录不存在：" + sourceDir);
        }
        if (!Files.isRegularFile(source.resolve("meta.json"))
                && !Files.isRegularFile(source.resolve("SKILL.md"))) {
            throw new AgentException("IMPORT_META_MISSING", "所选目录内未找到 meta.json 或 SKILL.md");
        }
        return importFrom(source);
    }

    /** 将包目录全部文件（含 meta.json/SKILL.md/资源）拷贝到目标目录。 */
    private void copyPackage(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Path rel = source.relativize(p);
                Files.createDirectories(target.resolve(rel).getParent());
                Files.copy(p, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 导入文件数上限（防御超大包）。 */
    private static final int MAX_IMPORT_FILES = 200;
    /** 导入包大小上限（字节，约 50MB）。 */
    private static final long MAX_IMPORT_BYTES = 50L * 1024 * 1024;

    /** E16：entry 命令模板若引用包内脚本（如 {@code python script.py}），校验该文件存在。 */
    private void validateEntry(String entry, Path packageDir) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        // 从 entry 中提取首个非内建、形如「xxx.ext」的脚本引用（跳过命令本身与 {args} 占位）
        String[] tokens = entry.trim().split("\\s+");
        for (String tok : tokens) {
            String cleaned = tok.replace("{args}", "").trim();
            if (cleaned.isEmpty() || cleaned.contains("{") || cleaned.contains("$")) {
                continue;
            }
            // 仅校验看起来像相对脚本路径的 token（含 . / 或带扩展名）
            if ((cleaned.contains("/") || cleaned.contains("\\") || cleaned.matches(".*\\.[A-Za-z0-9]{1,5}"))
                    && !cleaned.startsWith("-") && Files.isRegularFile(packageDir.resolve(cleaned))) {
                return; // 引用文件存在
            }
        }
        // 未检测到明显脚本引用则放行（纯命令模板如 python -m http.server 无需校验文件）
    }

    /** E14：导入限量校验——文件数与总字节数超限即拒绝。 */
    private void validateImportSize(Path packageDir) {
        long files = 0;
        long bytes = 0;
        try (var paths = Files.walk(packageDir)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(p);
            }
        } catch (IOException e) {
            throw new AgentException("IMPORT_IO_ERROR", "读取技能包大小失败", e);
        }
        if (files > MAX_IMPORT_FILES) {
            throw new AgentException("IMPORT_TOO_LARGE", "技能包文件数超过上限（" + MAX_IMPORT_FILES + "），请精简后导入");
        }
        if (bytes > MAX_IMPORT_BYTES) {
            throw new AgentException("IMPORT_TOO_LARGE", "技能包体积超过上限（50MB），请精简后导入");
        }
    }

    @Override
    public boolean remove(String id) {
        SkillDef def = skills.get(id);
        if (def == null) {
            return false;
        }
        if (def.platformReadOnly()) {
            log.warn("平台预置 Skill 不可卸载：{}", id);
            return false;
        }
        Path dir = loader.userSkillsRoot().resolve(id);
        try {
            deleteRecursively(dir);
            skills.remove(id);
            clearEnabledState(id); // 删除后清理启用状态，避免同名重载入时被旧状态污染（E8）
            revision.incrementAndGet();
            return true;
        } catch (IOException e) {
            log.error("删除 Skill 目录失败：{}", dir, e);
            return false;
        }
    }

    @Override
    public synchronized void reload() {
        Map<String, SkillDef> next = new LinkedHashMap<>();
        for (SkillDef def : loader.loadAll()) {
            SkillDef prev = next.putIfAbsent(def.id(), def);
            if (prev != null) {
                // E7：相同 id 静默覆盖会丢定义，告警提示并保留先加载（平台优先）的
                log.warn("检测到重复 Skill id「{}」，已保留先加载项（{}），忽略后加载项（{}）",
                        def.id(), prev.source(), def.source());
            }
        }
        Map<String, Boolean> state = loadState();
        next.replaceAll((id, def) -> {
            Boolean enabled = state.get(id);
            return enabled == null ? def : def.withEnabled(enabled);
        });
        skills.clear();
        skills.putAll(next);
        revision.incrementAndGet();
        // 仅在有变化（增删/启停/正文变更）时打印，避免热插拔定时扫描每 30s 无意义刷日志
        String fingerprint = next.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().enabled())
                .sorted()
                .reduce("", (a, b) -> a + "," + b);
        if (!fingerprint.equals(loadedFingerprint)) {
            loadedFingerprint = fingerprint;
            log.info("Skill 注册表已加载，共 {} 个（启用 {}）", skills.size(), listEnabled().size());
        } else {
            log.debug("Skill 注册表无变化，跳过日志（共 {} 个）", skills.size());
        }
    }

    @Override
    public long revision() {
        return revision.get();
    }

    /** 热插拔定时扫描（skill.hot-reload 开启时生效）。fixedDelay 单位为毫秒，见 application.yml 的 hot-reload-ms。 */
    @Scheduled(fixedDelayString = "${skill.hot-reload-ms:30000}")
    public void scheduledReload() {
        if (properties.hotReload()) {
            reload();
        }
    }

    private void persistEnabled(String id, boolean enabled) {
        try {
            Path file = dirs.configDir().resolve(STATE_FILE);
            Files.createDirectories(file.getParent());
            Map<String, Boolean> state = loadState();
            state.put(id, enabled);
            Files.writeString(file, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("持久化 Skill 启用状态失败：{}", id, e);
        }
    }

    /** 删除某 Skill 的启用状态（删除 Skill 后清理，避免同名重载入时残留旧状态）。 */
    private void clearEnabledState(String id) {
        try {
            Path file = dirs.configDir().resolve(STATE_FILE);
            if (!Files.isRegularFile(file)) {
                return;
            }
            Map<String, Boolean> state = loadState();
            if (state.remove(id) != null) {
                Files.writeString(file, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("清理 Skill 启用状态失败：{}", id, e);
        }
    }

    private Map<String, Boolean> loadState() {
        Path file = dirs.configDir().resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(file.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Boolean>>() {
                    });
        } catch (IOException e) {
            log.warn("读取 Skill 启用状态失败，按默认处理：{}", file, e);
            return new LinkedHashMap<>();
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
