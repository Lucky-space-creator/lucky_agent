package com.lucky.agent.executor.support.rollback;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 检查点清单（随每个快照目录落盘为 {@code __manifest__.json}，台账 #12）。
 *
 * <p>快照目录里只放「操作前已存在的文件副本」，所以单看目录无法区分
 * 「操作前这个文件不存在」与「存在但没被快照」。缺了这条信息，还原就只能覆盖、
 * 不能删除 → 新建的文件、MKDIR 的新目录、RENAME 的目标全部残留，
 * 用户看到的是「回滚了但产物还在」。</p>
 *
 * @param checkpointId 检查点 ID
 * @param entries      受影响路径及「操作前是否存在」
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotManifest(String checkpointId, List<Entry> entries) {

    /** 单个受影响路径。{@code existed=false} 表示该路径由本次操作创建，还原时应删除。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String path, boolean existed) {
    }

    public static SnapshotManifest of(String checkpointId, List<Entry> entries) {
        return new SnapshotManifest(checkpointId, entries == null ? List.of() : List.copyOf(entries));
    }
}
