package com.lucky.agent.permission.service.impl;

import com.lucky.agent.permission.service.WorkspaceRegistry;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 本地工作空间注册查询：只读取 {@code agent-workspace} 维护的映射（写入归 agent-workspace）。
 *
 * <p>注册时校验路径本机存在，不写入映射。</p>
 */
@Service
public class LocalWorkspaceRegistry implements WorkspaceRegistry {

    private final WorkspaceConfig workspaceConfig;

    public LocalWorkspaceRegistry(WorkspaceConfig workspaceConfig) {
        this.workspaceConfig = workspaceConfig;
    }

    @Override
    public Optional<Workspace> findByPath(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        String normalized = path.toAbsolutePath().normalize().toString();
        return workspaceConfig.listWorkspaces().stream()
                .filter(w -> w.path().equalsIgnoreCase(normalized))
                .findFirst();
    }

    @Override
    public boolean isRegistered(String workspaceId) {
        return workspaceConfig.getWorkspace(workspaceId).isPresent();
    }

    @Override
    public List<Workspace> listRegistered() {
        return workspaceConfig.listWorkspaces();
    }
}
