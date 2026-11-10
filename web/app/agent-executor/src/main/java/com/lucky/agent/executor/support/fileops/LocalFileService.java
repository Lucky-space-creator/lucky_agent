package com.lucky.agent.executor.support.fileops;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.ExecutorClient;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 本机文件服务：构建文件操作指令 → 权限裁决 → 执行臂执行。
 *
 * <p>所有操作经权限转发 + 执行臂硬边界校验，产物只落本机工作空间目录。</p>
 *
 * <p><b>为什么 {@link #list} 是延迟执行</b>：这些方法在「组装 Mono」时就同步做完权限裁决与
 * 执行臂往返，调用方拿到的其实是「已经跑完的结果」。对写操作这没问题（本就要落盘），
 * 但 {@code list} 会被 JLine 的<b>同步补全回调</b>调用 —— 组装即 IO 意味着首次 Tab / 启动预取
 * 直接卡在输入线程上，后面的 {@code subscribeOn} 也挪不走已经发生的读盘。故只读的
 * {@code list} 改为 {@code Mono.defer(...).subscribeOn(boundedElastic())}：
 * 组装阶段零 IO，订阅时才在下沉线程池里干活。</p>
 */
@Service
public class LocalFileService implements FileService {

    private final ExecutorClient executorClient;
    private final PermissionService permissionService;

    public LocalFileService(ExecutorClient executorClient, PermissionService permissionService) {
        this.executorClient = executorClient;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ExecResult> read(String workspaceId, String path) {
        return execute(FileOp.of(FileOp.OpType.READ, workspaceId, path));
    }

    @Override
    public Mono<ExecResult> write(String workspaceId, String path, String content) {
        return execute(FileOp.of(FileOp.OpType.WRITE, workspaceId, path).content(content));
    }

    @Override
    public Mono<ExecResult> list(String workspaceId, String path) {
        // 组装阶段零 IO：见类注释「为什么 list 是延迟执行」
        return Mono.defer(() -> execute(FileOp.of(FileOp.OpType.LIST, workspaceId, path)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ExecResult> delete(String workspaceId, String path) {
        return execute(FileOp.of(FileOp.OpType.DELETE, workspaceId, path));
    }

    @Override
    public Mono<ExecResult> stat(String workspaceId, String path) {
        return execute(FileOp.of(FileOp.OpType.STAT, workspaceId, path));
    }

    @Override
    public Mono<ExecResult> mkdir(String workspaceId, String path) {
        return execute(FileOp.of(FileOp.OpType.MKDIR, workspaceId, path));
    }

    @Override
    public Mono<ExecResult> rename(String workspaceId, String from, String to) {
        return execute(FileOp.of(FileOp.OpType.RENAME, workspaceId, from)
                .args(java.util.Map.of("to", to)));
    }

    @Override
    public Mono<ExecResult> exec(String workspaceId, String command) {
        return execute(FileOp.of(FileOp.OpType.EXEC, workspaceId, null)
                .args(Map.of("command", command)));
    }

    private Mono<ExecResult> execute(FileOp op) {
        PermissionDecision decision = permissionService.evaluateFileOp(op, op.workspaceId());
        return executorClient.execute(op, decision);
    }
}
