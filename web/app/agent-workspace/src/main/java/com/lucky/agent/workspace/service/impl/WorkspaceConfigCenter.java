package com.lucky.agent.workspace.service.impl;

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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** 项目规则文件名（位于工作空间根，与 skills/mcp/memory/config 并列，用户可直接编辑）。 */
    public static final String WS_RULES_FILE = "LUCKY.md";

    /** 项目规则默认模板（首次创建工作空间时写入，用户可直接编辑）。 */
    private static final String WS_RULES_TEMPLATE = """
            <!-- LUCKY Agent 项目规则（当前工作空间专属）
                 每次模型调用前读取，保存后即时生效，无需重启。
                 写本项目相关的约定、约束、技术栈、注意事项；全局规则见 <frameworkRoot>/LUCKY.md。 -->

            # 项目规则
            （在此编写本项目的专属规则…）
            """;

    private final Path storeFile;
    private final WorkspaceDirs dirs;
    private final ObjectMapper objectMapper;
    /**
     * 工作空间清单。
     *
     * <p>用 {@link CopyOnWriteArrayList} 而非 {@code ArrayList}（台账 #23）：本类的读方法
     * （{@code findById} / {@code listWorkspaces} / {@code permissionLevelOf}）被执行臂、工具、
     * REST 多线程并发调用，而写只发生在「创建工作空间 / 改权限 / 删除」这类低频动作上。
     * 原实现读路径无锁、写路径加锁，迭代期间被并发修改会抛
     * {@code ConcurrentModificationException}（{@code listWorkspaces()} 正是遍历建副本）。</p>
     */
    private final List<Workspace> workspaces = new CopyOnWriteArrayList<>();

    /**
     * 上次 {@link #load()} 是否失败。
     *
     * <p>失败后 {@code workspaces} 是空列表，此时若直接保存，会用 {@code []} 覆盖掉那份
     * <b>可能只是格式错误、但仍可人工修复</b>的原文件 —— 用户「所有工作空间一夜消失」。
     * 因此失败后首次保存前先把原文件改名备份（见 {@link #backupIfLoadFailed()}）。</p>
     */
    private volatile boolean loadFailed;

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
            if (loaded == null) {
                // 空文件（{} / null）也按「解析不出清单」处理，但不备份：没有数据可丢
                log.warn("工作空间配置文件内容为空：{}", storeFile);
                return;
            }
            workspaces.clear();
            for (Workspace w : loaded) {
                if (w != null) {
                    workspaces.add(w);
                }
            }
            log.info("加载工作空间配置，共 {} 个", workspaces.size());
        } catch (IOException | RuntimeException e) {
            // 台账 #23：此前只 log.error 后继续用空列表跑，随后任一次 save 就把原文件覆盖成 []。
            // 现在置标记，让第一次保存先备份原文件，把「静默丢数据」变成「可恢复的告警」。
            loadFailed = true;
            log.error("加载工作空间配置失败，已阻止后续写入直接覆盖原文件"
                    + "（首次保存前会把原文件备份为 {}.corrupt-<时间戳>）：{}", FILE_NAME, storeFile, e);
        }
    }

    /**
     * 保存清单：先写同目录临时文件再原子替换。
     *
     * <p>原实现用 {@code TRUNCATE_EXISTING} 直接写目标文件 —— 先截断后写，进程若在中间崩溃
     * （或磁盘写失败），留下的就是半截 JSON。下次启动 {@link #load()} 解析失败，
     * 再叠加「空列表覆盖」的旧行为，等于一次崩溃丢掉全部工作空间。改用
     * 「临时文件 + {@code ATOMIC_MOVE}」后，读方永远看到完整的旧版本或完整的新版本。</p>
     */
    private synchronized void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            backupIfLoadFailed();
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(workspaces);
            Path tmp = Files.createTempFile(storeFile.getParent(), FILE_NAME + ".", ".tmp");
            try {
                Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(tmp, storeFile,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // 个别文件系统（部分网络盘/FAT）不支持原子移动：退化为普通替换，
                    // 仍有截断窗口，但至少不会写出「半截内容」到目标文件的中间态以外的语义
                    log.warn("当前文件系统不支持原子移动，已退化为普通替换：{}", storeFile);
                    Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            loadFailed = false;
        } catch (IOException e) {
            log.error("保存工作空间配置失败：{}", storeFile, e);
        }
    }

    /** 若上次加载失败，先把原文件备份一份，避免「解析失败 → 空列表覆盖」造成不可恢复的数据丢失。 */
    private void backupIfLoadFailed() throws IOException {
        if (!loadFailed || !Files.exists(storeFile)) {
            return;
        }
        Path backup = storeFile.resolveSibling(FILE_NAME + ".corrupt-" + System.currentTimeMillis());
        Files.copy(storeFile, backup, StandardCopyOption.REPLACE_EXISTING);
        log.warn("上次配置加载失败，已将原文件备份为 {}，随后写入的是新清单；原数据可从备份人工恢复", backup);
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
        // 项目规则文件（工作空间根 LUCKY.md）：不存在则写入模板，用户可直接编辑
        try {
            ensureWorkspaceRules(path);
        } catch (Exception e) {
            log.warn("生成项目规则文件失败（可忽略）：{}", path, e);
        }
        log.info("创建工作空间：{} -> {}", name, workspace.path());
        return workspace;
    }

    /** 若工作空间根下没有 LUCKY.md 规则文件，则写入默认模板（幂等，不覆盖用户已有内容）。 */
    private void ensureWorkspaceRules(Path workspacePath) throws IOException {
        Path rulesFile = workspacePath.toAbsolutePath().normalize().resolve(WS_RULES_FILE);
        if (Files.exists(rulesFile)) {
            return;
        }
        Files.writeString(rulesFile, WS_RULES_TEMPLATE, StandardCharsets.UTF_8);
        log.info("已生成项目规则文件：{}", rulesFile);
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
