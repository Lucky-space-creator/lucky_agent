package com.lucky.agent.mcp.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.config.McpProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * MCP 磁盘加载器：扫描用户自建 MCP 配置目录（{@code <root>/mcp/}）。
 *
 * <p>每个 MCP Server 一个文件夹：{@code mcp/<id>/meta.json}，内容即
 * {@link McpServerDef}，便于一个服务关联其自有资源。平台预置 MCP 列表随发行配置下发，
 * 不在本目录扫描范围。</p>
 */
@Slf4j
public class McpLoader {

    private static final String META_FILE = "meta.json";

    private final WorkspaceDirs dirs;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpLoader(WorkspaceDirs dirs, McpProperties properties, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 用户自建 MCP 根目录（{@code <frameworkRoot>/mcp}）。 */
    public Path userMcpRoot() {
        return dirs.frameworkRoot().resolve(properties.userDir());
    }

    /** 某 MCP Server 的配置目录（{@code mcp/<id>}）。 */
    public Path serverDir(String id) {
        return userMcpRoot().resolve(id);
    }

    /** 某 MCP Server 的配置文件路径（{@code mcp/<id>/meta.json}）。 */
    public Path serverFile(String id) {
        return serverDir(id).resolve(META_FILE);
    }

    /** 扫描全部 MCP Server 配置。 */
    public List<McpServerDef> loadAll() {
        Path root = userMcpRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<McpServerDef> defs = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(root)) {
            subdirs.filter(Files::isDirectory)
                    .map(this::loadMeta)
                    .flatMap(Optional::stream)
                    .forEach(defs::add);
        } catch (IOException e) {
            log.warn("扫描 MCP 目录失败：{}", root, e);
        }
        return defs;
    }

    private Optional<McpServerDef> loadMeta(Path dir) {
        Path file = dir.resolve(META_FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            McpServerDef def = objectMapper.readValue(file.toFile(), McpServerDef.class);
            if (def.id() == null || def.id().isBlank()) {
                log.warn("MCP 配置缺少 id，忽略：{}", file);
                return Optional.empty();
            }
            return Optional.of(def);
        } catch (IOException e) {
            log.warn("解析 MCP 配置失败：{}", file, e);
            return Optional.empty();
        }
    }
}
