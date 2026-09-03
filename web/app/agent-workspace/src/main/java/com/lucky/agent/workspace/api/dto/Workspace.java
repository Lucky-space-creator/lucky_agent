package com.lucky.agent.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lucky.agent.common.constant.PermissionLevel;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 工作空间领域对象。
 *
 * <p>轻量命名空间：工作区即「一个工作区 / 一个需求会话」，资源按 workspaceId 在本机隔离。
 * 物理路径与权限级别随配置存 {@code <frameworkRoot>/.config/workspaces.json}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
public class Workspace {

    @JsonProperty("workspaceId")
    private String workspaceId;
    @JsonProperty("name")
    private String name;
    @JsonProperty("path")
    private String path;
    @JsonProperty("permissionLevel")
    private PermissionLevel permissionLevel;
    @JsonProperty("createdAt")
    private Instant createdAt;
    /**
     * 内置默认工作空间：位于框架根内（{@code <user.home>/.lucky_agent/workspace}），
     * 由启动流程自动注册，前端不展示、不可删除，仅在用户未自建工作空间时兜底生效。
     */
    @JsonProperty("builtin")
    private boolean builtin;

    public static Workspace of(String workspaceId, String name, String path,
                               PermissionLevel permissionLevel, Instant createdAt) {
        Workspace w = new Workspace();
        w.workspaceId = workspaceId;
        w.name = name;
        w.path = path;
        w.permissionLevel = permissionLevel;
        w.createdAt = createdAt;
        return w;
    }
}
