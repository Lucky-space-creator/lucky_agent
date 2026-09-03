package com.lucky.agent.executor.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 回退快照（按 workspaceId 分桶，存 {@code <frameworkRoot>/.rollback/}）。
 *
 * @param checkpointId 检查点 ID
 * @param workspaceId  工作空间 ID
 * @param createdAt    创建时间
 * @param parentId     父检查点（支持逐步回退）
 * @param affectedFiles 受影响文件（相对路径）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Snapshot(
        String checkpointId,
        String workspaceId,
        Instant createdAt,
        String parentId,
        List<String> affectedFiles) {

    public static Snapshot of(String checkpointId, String workspaceId, Instant createdAt,
                              String parentId, List<String> affectedFiles) {
        return new Snapshot(checkpointId, workspaceId, createdAt, parentId, affectedFiles);
    }
}
