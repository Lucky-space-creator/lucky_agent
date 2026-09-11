package com.lucky.agent.workspace.api;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.workspace.api.dto.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 工作空间命名空间管理契约（§4.8）。
 *
 * <p>建名即用：创建时生成 workspaceId，并将「名称 ↔ 路径 ↔ 权限级别」写入
 * {@code <frameworkRoot>/.config/workspaces.json}（框架隐藏目录，零托管）。</p>
 */
@Remote(serviceName = "com/lucky/agent/workspace")
public interface WorkspaceManager {

    /**
     * 创建并注册工作空间。
     *
     * @param name            工作空间名称
     * @param path            本机物理目录
     * @param permissionLevel 权限级别（只读/修改/全部）
     * @return 已注册的工作空间（含生成的 workspaceId）
     * @throws IllegalArgumentException path 不存在或名称重复
     */
    Workspace createWorkspace(String name, Path path, PermissionLevel permissionLevel);

    /**
     * 注册内置默认工作空间（幂等）。
     *
     * <p>内置工作空间位于框架根内（{@code <user.home>/.lucky_agent/workspace}），
     * 是用户未自建工作空间时的兜底落盘位置；前端不展示、不可删除。
     * 已存在同名但指向旧路径的注册（旧版本把默认工作空间建在框架根上）会被清理，
     * 仅移除注册、不删物理目录。</p>
     *
     * @param name            固定名称
     * @param path            内置工作空间物理目录（须已存在）
     * @param permissionLevel 权限级别
     * @return 内置工作空间（已标记 builtin）
     */
    Workspace ensureBuiltinWorkspace(String name, Path path, PermissionLevel permissionLevel);

    /**
     * 按 id 查询工作空间。
     *
     * @param workspaceId 工作空间 ID
     * @return 存在则返回，否则空
     */
    Optional<Workspace> findById(String workspaceId);

    /**
     * 按名称查询工作空间。
     *
     * @param name 名称
     * @return 存在则返回，否则空
     */
    Optional<Workspace> findByName(String name);

    /** 列出全部工作空间。 */
    List<Workspace> listWorkspaces();

    /** 更新工作空间权限级别。 */
    Optional<Workspace> updatePermissionLevel(String workspaceId, PermissionLevel permissionLevel);

    /** 删除工作空间注册（不删除物理目录）。 */
    boolean removeWorkspace(String workspaceId);
}
