package com.lucky.agent.executor.api;

import com.lucky.agent.executor.api.dto.Snapshot;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 执行回退契约（§4.7，本机快照）。
 *
 * <p>关键操作前在本机生成快照（按 workspaceId 分桶存 {@code .rollback/}），
 * 回退即还原本机快照，不依赖消息队列；用户感知为「一键撤销」，粒度 = 检查点。</p>
 */
@com.lucky.agent.common.contract.Remote(serviceName = "rollback")
public interface RollbackService {

    /** 创建检查点快照（记录受影响文件副本）。 */
    Mono<Snapshot> checkpoint(String workspaceId, List<String> affectedFiles);

    /** 查询某工作空间最近的检查点。 */
    Optional<Snapshot> latestCheckpoint(String workspaceId);

    /** 按 checkpointId 回退（还原本机快照）。 */
    Mono<Boolean> rollback(String workspaceId, String checkpointId);

    /** 列出某工作空间全部检查点。 */
    List<Snapshot> listCheckpoints(String workspaceId);
}
