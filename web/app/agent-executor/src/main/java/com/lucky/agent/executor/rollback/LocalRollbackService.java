package com.lucky.agent.executor.rollback;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.executor.api.RollbackService;
import com.lucky.agent.executor.api.dto.Snapshot;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 本机回退服务：检查点快照 + 一键撤销（按 workspaceId 分桶�?{@code .rollback/}）�? */

@Slf4j
@Service
public class LocalRollbackService implements RollbackService {

    
    private final WorkspaceDirs dirs;
    private final WorkspaceConfig workspaceConfig;
    private final CheckpointSnapshoter snapshoter;
    private final Compensator compensator;
    private final SnapshotIndex snapshotIndex;

    public LocalRollbackService(WorkspaceDirs dirs, WorkspaceConfig workspaceConfig,
                                CheckpointSnapshoter snapshoter, Compensator compensator,
                                SnapshotIndex snapshotIndex) {
        this.dirs = dirs;
        this.workspaceConfig = workspaceConfig;
        this.snapshoter = snapshoter;
        this.compensator = compensator;
        this.snapshotIndex = snapshotIndex;
    }

    @Override
    public Mono<Snapshot> checkpoint(String workspaceId, List<String> affectedFiles) {
        Path root = workspaceRoot(workspaceId);
        return Mono.fromCallable(() -> snapshoter.checkpoint(workspaceId, root, affectedFiles));
    }

    @Override
    public Optional<Snapshot> latestCheckpoint(String workspaceId) {
        List<Snapshot> all = snapshotIndex.read(dirs.rollbackDir(), workspaceId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public Mono<Boolean> rollback(String workspaceId, String checkpointId) {
        Path root = workspaceRoot(workspaceId);
        return Mono.fromCallable(() -> {
            boolean restored = compensator.restore(workspaceId, root, checkpointId);
            if (restored) {
                List<Snapshot> all = snapshotIndex.read(dirs.rollbackDir(), workspaceId);
                all.removeIf(s -> s.checkpointId().equals(checkpointId));
                snapshotIndex.write(dirs.rollbackDir(), workspaceId, all);
                log.info("已还原检查点：wid={} checkpoint={}", workspaceId, checkpointId);
            }
            return restored;
        });
    }

    @Override
    public List<Snapshot> listCheckpoints(String workspaceId) {
        return snapshotIndex.read(dirs.rollbackDir(), workspaceId);
    }

    private Path workspaceRoot(String workspaceId) {
        return Path.of(workspaceConfig.physicalPathOf(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("工作空间未注册：" + workspaceId)));
    }
}
