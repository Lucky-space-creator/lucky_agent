package com.lucky.agent.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.lucky.agent.common.constant.WorkspaceDirs;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户授权隔离：MCP 需用户显式"授权连接"后才注入其 Tool。
 *
 * <p>授权集合持久化在 {@code <frameworkRoot>/.config/mcp-auth.json}
 * （JSON 字符串数组，即 serverId 列表），不依赖服务端；撤销授权后对应
 * Tool 从下一轮网关注销。</p>
 */
@Slf4j
public class UserAuthIsolator {

    private static final String AUTH_FILE = "mcp-auth.json";

    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;

    private final Set<String> authorized = ConcurrentHashMap.newKeySet();

    public UserAuthIsolator(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.objectMapper = objectMapper;
    }

    /** 启动加载授权集合。 */
    public synchronized void load() {
        authorized.clear();
        authorized.addAll(readFile());
        log.info("MCP 授权集合已加载，共 {} 个 Server", authorized.size());
    }

    /** 是否已授权某 MCP Server。 */
    public boolean isAuthorized(String serverId) {
        return authorized.contains(serverId);
    }

    /** 已授权的 Server id 列表。 */
    public List<String> authorizedList() {
        return new ArrayList<>(authorized);
    }

    /** 授权某 MCP Server 并持久化。 */
    public synchronized void authorize(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        authorized.add(serverId);
        writeFile();
    }

    /** 撤销某 MCP Server 授权并持久化，返回是否撤销成功。 */
    public synchronized boolean revoke(String serverId) {
        boolean removed = authorized.remove(serverId);
        if (removed) {
            writeFile();
        }
        return removed;
    }

    private Set<String> readFile() {
        Path file = dirs.configDir().resolve(AUTH_FILE);
        if (!Files.isRegularFile(file)) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(file.toFile(),
                    new TypeReference<List<String>>() {
                    }));
        } catch (IOException e) {
            log.warn("读取 MCP 授权集合失败，按空处理：{}", file, e);
            return new HashSet<>();
        }
    }

    private void writeFile() {
        try {
            Path file = dirs.configDir().resolve(AUTH_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, objectMapper.writeValueAsString(new ArrayList<>(authorized)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("持久化 MCP 授权集合失败", e);
        }
    }
}
