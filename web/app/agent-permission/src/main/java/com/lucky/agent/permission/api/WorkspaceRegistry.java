package com.lucky.agent.permission.api;

import com.lucky.agent.workspace.api.dto.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 工作空间注册契约。
 *
 * <p>只读取 {@code agent-workspace} 维护的 {@code workspaceId ↔ 物理路径} 映射（写入归 agent-workspace），
 * 注册时校验路径本机存在，不写入映射。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "workspace-registry")
public interface WorkspaceRegistry {

    /**
     * 校验路径是否已被注册为工作空间。
     *
     * @param path 本机物理目录
     * @return 命中则返回对应工作空间
     */
    Optional<Workspace> findByPath(Path path);

    /**
     * 校验工作空间是否已注册。
     *
     * @param workspaceId 工作空间 ID
     * @return 已注册返回 true
     */
    boolean isRegistered(String workspaceId);

    /** 列出全部已注册工作空间。 */
    List<Workspace> listRegistered();
}
