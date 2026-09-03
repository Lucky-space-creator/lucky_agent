package com.lucky.agent.workspace.api;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.workspace.api.dto.Workspace;

import java.util.List;
import java.util.Optional;

/**
 * 工作空间配置中心契约。
 *
 * <p>对外提供「某工作区的权限级别」与「物理路径」两路查询；
 * permission 模块直接读取这里的权限级别并带入对话上下文，保证权限判定与配置同源、单一事实。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "workspace-config")
public interface WorkspaceConfig {

    /**
     * 查询工作区权限级别。
     *
     * @param workspaceId 工作空间 ID
     * @return 权限级别；不存在返回默认级别（修改文件）
     */
    Optional<PermissionLevel> permissionLevelOf(String workspaceId);

    /**
     * 查询工作区物理路径。
     *
     * @param workspaceId 工作空间 ID
     * @return 物理路径；不存在返回空
     */
    Optional<String> physicalPathOf(String workspaceId);

    /**
     * 查询工作区完整对象。
     *
     * @param workspaceId 工作空间 ID
     * @return 工作空间；不存在返回空
     */
    Optional<Workspace> getWorkspace(String workspaceId);

    /** 列出全部已注册工作空间。 */
    List<Workspace> listWorkspaces();
}
