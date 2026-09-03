package com.lucky.agent.web.controller;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workspace.api.dto.Workspace;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作区管理接口。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceManager workspaceManager;

    public WorkspaceController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 列出全部工作空间（含内置默认项）。
     *
     * <p>内置默认工作空间带 {@code builtin=true}：前端不展示、不可删除，
     * 但在用户尚未自建工作空间时作为兜底落盘位置，故仍需返回其 id 供会话/文件接口使用。</p>
     */
    @GetMapping
    public List<Workspace> list() {
        return workspaceManager.listWorkspaces().stream()
                .map(this::withPath)
                .toList();
    }

    /** 创建工作空间。 */
    @PostMapping
    public Workspace create(@RequestBody CreateRequest request) {
        return withPath(workspaceManager.createWorkspace(request.name(), Path.of(request.path()),
                PermissionLevel.fromCode(request.permissionLevel())));
    }

    /** 工作空间详情。 */
    @GetMapping("/{workspaceId}")
    public Optional<Workspace> detail(@PathVariable String workspaceId) {
        return workspaceManager.findById(workspaceId).map(this::withPath);
    }

    /** 更新权限级别。 */
    @PutMapping("/{workspaceId}/permission")
    public Optional<Workspace> updatePermission(@PathVariable String workspaceId,
                                                @RequestBody Map<String, String> body) {
        return workspaceManager.updatePermissionLevel(workspaceId,
                PermissionLevel.fromCode(body.get("level"))).map(this::withPath);
    }

    /** 返回完整工作空间（含真实物理路径，供前端在界面展示；本机优先，路径不涉密）。 */
    private Workspace withPath(Workspace workspace) {
        return Workspace.of(workspace.workspaceId(), workspace.name(), workspace.path(),
                        workspace.permissionLevel(), workspace.createdAt())
                .builtin(workspace.builtin());
    }

    /** 删除工作空间注册（内置默认工作空间不可删除）。 */
    @DeleteMapping("/{workspaceId}")
    public Map<String, Boolean> remove(@PathVariable String workspaceId) {
        return Map.of("removed", workspaceManager.removeWorkspace(workspaceId));
    }

    /** 创建工作空间请求体。 */
    public record CreateRequest(String name, String path, String permissionLevel) {
    }
}
