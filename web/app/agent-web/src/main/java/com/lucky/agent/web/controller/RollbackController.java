package com.lucky.agent.web.controller;

import com.lucky.agent.executor.api.RollbackService;
import com.lucky.agent.executor.api.dto.Snapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 执行回退接口（§4.7 本机快照 + 一键撤销）。
 */
@RestController
@RequestMapping("/api/rollback")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    /** 创建检查点快照；请求体为受影响文件相对路径数组。 */
    @PostMapping("/{workspaceId}/checkpoints")
    public Mono<Snapshot> checkpoint(@PathVariable String workspaceId,
                                     @RequestBody List<String> affectedFiles) {
        List<String> files = affectedFiles == null ? List.of() : affectedFiles;
        return rollbackService.checkpoint(workspaceId, files);
    }

    /** 列出某工作空间全部检查点。 */
    @GetMapping("/{workspaceId}/checkpoints")
    public List<Snapshot> list(@PathVariable String workspaceId) {
        return rollbackService.listCheckpoints(workspaceId);
    }

    /** 最近检查点（无则返回 null）。 */
    @GetMapping("/{workspaceId}/checkpoints/latest")
    public Snapshot latest(@PathVariable String workspaceId) {
        return rollbackService.latestCheckpoint(workspaceId).orElse(null);
    }

    /** 按检查点回退；请求体 {@code {"checkpointId": "..."}}。 */
    @PostMapping("/{workspaceId}/rollback")
    public Mono<Map<String, Boolean>> rollback(@PathVariable String workspaceId,
                                               @RequestBody Map<String, String> body) {
        String checkpointId = body.get("checkpointId");
        return rollbackService.rollback(workspaceId, checkpointId).map(ok -> Map.of("restored", ok));
    }
}
