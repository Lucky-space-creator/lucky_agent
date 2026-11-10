package com.lucky.agent.executor.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 回退快照（按 workspaceId 分桶，存 {@code <frameworkRoot>/.rollback/}）。
 *
 * @param checkpointId  检查点 ID
 * @param workspaceId   工作空间 ID
 * @param createdAt     创建时间
 * @param parentId      父检查点（支持逐步回退）
 * @param affectedFiles 受影响文件（相对路径）
 * @param createdPaths  操作前<b>不存在</b>的受影响路径（台账 #12）——
 *                      WRITE 新建的文件、MKDIR 新建的目录、RENAME 的目标都属于这一类。
 *                      还原时必须把这些路径删掉，否则「回滚」会把新建产物留在工作区。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Snapshot(
        String checkpointId,
        String workspaceId,
        Instant createdAt,
        String parentId,
        List<String> affectedFiles,
        List<String> createdPaths) {

    /** 兼容重载：旧调用方不区分「操作前是否存在」，等价于所有受影响路径都视为已存在。 */
    public static Snapshot of(String checkpointId, String workspaceId, Instant createdAt,
                              String parentId, List<String> affectedFiles) {
        return of(checkpointId, workspaceId, createdAt, parentId, affectedFiles, List.of());
    }

    public static Snapshot of(String checkpointId, String workspaceId, Instant createdAt,
                              String parentId, List<String> affectedFiles, List<String> createdPaths) {
        return new Snapshot(checkpointId, workspaceId, createdAt, parentId,
                affectedFiles == null ? List.of() : affectedFiles,
                createdPaths == null ? List.of() : createdPaths);
    }
}
