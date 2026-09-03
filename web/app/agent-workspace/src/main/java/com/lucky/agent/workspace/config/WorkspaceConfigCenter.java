package com.lucky.agent.workspace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.dto.WorkspaceId;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 工作空间配置中心实现：维护 {@code workspaceId -> 物理路径 -> 权限级别} 映射。
 * <p>映射数据存于 {@code <frameworkRoot>/.config/workspaces.json}（框架隐藏目录），
 * 不依赖服务端数据库（本机优先零托管）。</p>
 */

@Slf4j
@Service
public class WorkspaceConfigCenter implements WorkspaceManager, WorkspaceConfig {

    
    private static final String FILE_NAME = "workspaces.json";

    private final Path storeFile;
    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;
    private final List<Workspace> workspaces = new ArrayList<>();

    public WorkspaceConfigCenter(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.storeFile = dirs.configDir().resolve(FILE_NAME);
        this.objectMapper = objectMapper;
        load();
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            Workspace[] loaded = objectMapper.readValue(json, Workspace[].class);
            workspaces.clear();
            for (Workspace w : loaded) {
                workspaces.add(w);
            }
            log.info("加载工作空间配置，共 {} 个", workspaces.size());
        } catch (IOException e) {
            log.error("加载工作空间配置失败：{}", storeFile, e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            byte[] bytes = objectMapper.writeValueAsBytes(workspaces);
            Files.write(storeFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("保存工作空间配置失败：{}", storeFile, e);
        }
    }

    @Override
    public synchronized Workspace createWorkspace(String name, Path path, PermissionLevel permissionLevel) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工作空间名称不能为空");
        }
        if (findByName(name).isPresent()) {
            throw new IllegalArgumentException("工作空间名称已存在：" + name);
        }
        if (path == null || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("目录不存在：" + path);
        }
        // 硬边界：禁止把框架根（隐藏目录所在处）注册为工作空间，避免用户产物与 Agent 内容混写
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(dirs.frameworkRoot().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("不可将框架目录注册为工作空间，请选择其他目录");
        }
        Workspace workspace = Workspace.of(
                WorkspaceId.generate().value(),
                name,
                path.toAbsolutePath().normalize().toString(),
                permissionLevel == null ? PermissionLevel.defaultValue() : permissionLevel,
                Instant.now());
        workspaces.add(workspace);
        save();
        // 在工作空间物理路径下创建用户可见、可编辑的配置子树（skills/ mcp/ memory/ config/）
        try {
            dirs.ensureWorkspaceStructure(path);
        } catch (Exception e) {
            log.warn("初始化工作空间可见子目录失败（可忽略）：{}", path, e);
        }
        log.info("创建工作空间：{} -> {}", name, workspace.path());
        return workspace;
    }

    @Override
    public synchronized Workspace ensureBuiltinWorkspace(String name, Path path, PermissionLevel permissionLevel) {
        Path target = path.toAbsolutePath().normalize();
        String targetPath = target.toString();
        Optional<Workspace> byPath = workspaces.stream()
                .filter(w -> targetPath.equals(w.path()))
                .findFirst();
        if (byPath.isPresent()) {
            Workspace existing = byPath.get();
            if (!existing.builtin()) {
                existing.builtin(true);
                save();
            }
            return existing;
        }
        // 旧版本遗留：同名工作空间仍指向框架根，清理其注册（物理目录保留），避免产物写进框架隐藏目录
        Optional<Workspace> legacy = findByName(name);
        if (legacy.isPresent()) {
            log.info("清理旧版默认工作空间注册（物理目录保留）：{} -> {}", name, legacy.get().path());
            removeWorkspace(legacy.get().workspaceId());
        }
        Workspace builtin = createWorkspace(name, target, permissionLevel == null
                ? PermissionLevel.defaultValue() : permissionLevel);
        builtin.builtin(true);
        save();
        return builtin;
    }

    @Override
    public Optional<Workspace> findById(String workspaceId) {
        return workspaces.stream().filter(w -> w.workspaceId().equals(workspaceId)).findFirst();
    }

    @Override
    public Optional<Workspace> findByName(String name) {
        return workspaces.stream().filter(w -> w.name().equals(name)).findFirst();
    }

    @Override
    public List<Workspace> listWorkspaces() {
        return List.copyOf(workspaces);
    }

    @Override
    public synchronized Optional<Workspace> updatePermissionLevel(String workspaceId, PermissionLevel permissionLevel) {
        Optional<Workspace> target = findById(workspaceId);
        target.ifPresent(w -> {
            w.permissionLevel(permissionLevel);
            save();
        });
        return target;
    }

    @Override
    public synchronized boolean removeWorkspace(String workspaceId) {
        Optional<Workspace> target = findById(workspaceId);
        if (target.isPresent() && target.get().builtin()) {
            throw new IllegalArgumentException("内置默认工作空间不可删除");
        }
        boolean removed = workspaces.removeIf(w -> w.workspaceId().equals(workspaceId));
        if (removed) {
            save();
        }
        return removed;
    }

    @Override
    public Optional<PermissionLevel> permissionLevelOf(String workspaceId) {
        return findById(workspaceId).map(Workspace::permissionLevel);
    }

    @Override
    public Optional<String> physicalPathOf(String workspaceId) {
        return findById(workspaceId).map(Workspace::path);
    }

    @Override
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return findById(workspaceId);
    }
}
