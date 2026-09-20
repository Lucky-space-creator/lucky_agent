package com.lucky.agent.mcp.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 MCP 注册中心：持有 MCP Server 配置与启用状态，支持磁盘重载。
 *
 * <p>配置落盘 {@code <frameworkRoot>/mcp/<id>/meta.json}（用户自建，一个服务一个文件夹）；
 * 启用状态写在配置文件本身。授权集合（"授权连接"）不归本类管理，见 {@code UserAuthIsolator}。</p>
 */
@Slf4j
public class LocalMcpRegistry implements McpRegistry {

    private final McpLoader loader;
    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;

    private final Map<String, McpServerDef> servers = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    public LocalMcpRegistry(McpLoader loader, WorkspaceDirs dirs, ObjectMapper objectMapper,
                            McpProperties properties) {
        this.loader = loader;
        this.dirs = dirs;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 首次扫描加载（由配置类在 Bean 装配后调用）。 */
    public void init() {
        reload();
    }

    @Override
    public List<McpServerDef> list() {
        return new ArrayList<>(servers.values());
    }

    @Override
    public List<McpServerDef> listEnabled() {
        return servers.values().stream().filter(McpServerDef::enabled).toList();
    }

    @Override
    public Optional<McpServerDef> find(String id) {
        return Optional.ofNullable(servers.get(id));
    }

    @Override
    public void setEnabled(String id, boolean enabled) {
        McpServerDef current = servers.get(id);
        if (current == null) {
            return;
        }
        McpServerDef updated = current.withEnabled(enabled);
        servers.put(id, updated);
        persist(updated);
        revision.incrementAndGet();
    }

    @Override
    public McpServerDef save(McpServerDef def) {
        if (def == null || def.id() == null || def.id().isBlank()) {
            throw new IllegalArgumentException("MCP Server id 不能为空");
        }
        persist(def);
        servers.put(def.id(), def);
        revision.incrementAndGet();
        return def;
    }

    @Override
    public McpServerDef importFrom(Path packageDir) {
        Path meta = packageDir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            throw new AgentException("IMPORT_META_MISSING", "包内缺少 meta.json");
        }
        try {
            McpServerDef def = objectMapper.readValue(meta.toFile(), McpServerDef.class);
            if (def.id() == null || def.id().isBlank()) {
                throw new AgentException("IMPORT_META_INVALID", "meta.json 缺少 id");
            }
            Path target = loader.serverDir(def.id());
            deleteRecursively(target);
            Files.createDirectories(target);
            copyPackage(packageDir, target);
            reload();
            return find(def.id())
                    .orElseThrow(() -> new AgentException("IMPORT_FAILED", "导入后未找到 MCP Server：" + def.id()));
        } catch (IOException e) {
            throw new AgentException("IMPORT_IO_ERROR", "导入 MCP Server 失败", e);
        }
    }

    /** 将包目录全部文件（含 meta.json/资源）拷贝到目标目录。 */
    private void copyPackage(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Path rel = source.relativize(p);
                Files.createDirectories(target.resolve(rel).getParent());
                Files.copy(p, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override
    public boolean remove(String id) {
        McpServerDef def = servers.get(id);
        if (def == null) {
            return false;
        }
        Path dir = loader.serverDir(id);
        try {
            deleteRecursively(dir);
            servers.remove(id);
            revision.incrementAndGet();
            return true;
        } catch (IOException e) {
            log.error("删除 MCP 配置失败：{}", dir, e);
            return false;
        }
    }

    /** 递归删除目录（含 meta.json 及其自有资源），目录不存在视为成功。 */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Override
    public synchronized void reload() {
        Map<String, McpServerDef> next = new LinkedHashMap<>();
        for (McpServerDef def : loader.loadAll()) {
            next.put(def.id(), def);
        }
        servers.clear();
        servers.putAll(next);
        revision.incrementAndGet();
        log.info("MCP 注册表已加载，共 {} 个（启用 {}）", servers.size(), listEnabled().size());
    }

    @Override
    public long revision() {
        return revision.get();
    }

    /** 热加载定时扫描（mcp.hot-reload-ms 未配置时默认 60000 毫秒，供磁盘手动增删）。fixedDelay 单位为毫秒。 */
    @Scheduled(fixedDelayString = "${mcp.hot-reload-ms:60000}")
    public void scheduledReload() {
        reload();
    }

    private void persist(McpServerDef def) {
        try {
            Path file = loader.serverFile(def.id());
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(def),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存 MCP 配置失败：" + def.id(), e);
        }
    }
}
