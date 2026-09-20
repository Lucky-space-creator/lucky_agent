package com.lucky.agent.executor.support.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.executor.api.dto.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照索引：按 workspaceId 分桶，维护检查点列表（{@code .rollback/<wid>/index.json}）。
 */
public class SnapshotIndex {

    private final ObjectMapper objectMapper;

    public SnapshotIndex(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path indexFile(Path rollbackDir, String workspaceId) {
        return rollbackDir.resolve(workspaceId).resolve("index.json");
    }

    /** 读取某工作空间检查点列表（文件不存在返回空）。 */
    public List<Snapshot> read(Path rollbackDir, String workspaceId) {
        Path file = indexFile(rollbackDir, workspaceId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Snapshot[] snapshots = objectMapper.readValue(json, Snapshot[].class);
            return new ArrayList<>(List.of(snapshots));
        } catch (IOException e) {
            throw new IllegalStateException("读取快照索引失败：" + file, e);
        }
    }

    /** 追加一个检查点并写回索引。 */
    public void append(Path rollbackDir, Snapshot snapshot) {
        List<Snapshot> all = read(rollbackDir, snapshot.workspaceId());
        all.add(snapshot);
        write(rollbackDir, snapshot.workspaceId(), all);
    }

    /** 写回索引。 */
    public void write(Path rollbackDir, String workspaceId, List<Snapshot> snapshots) {
        Path file = indexFile(rollbackDir, workspaceId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, objectMapper.writeValueAsBytes(snapshots));
        } catch (IOException e) {
            throw new IllegalStateException("写快照索引失败：" + file, e);
        }
    }
}
