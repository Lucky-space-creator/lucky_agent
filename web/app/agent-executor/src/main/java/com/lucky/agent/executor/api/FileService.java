package com.lucky.agent.executor.api;

import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.common.dto.ExecResult;
import reactor.core.publisher.Mono;

/**
 * 文件服务契约（限工作空间）。
 *
 * <p>所有操作经权限转发 + 执行臂硬边界校验；产物只落本机工作空间目录，不触碰工作空间外。</p>
 */
@Remote(serviceName = "file-service")
public interface FileService {

    /** 读取文件内容（仅工作空间内）。 */
    Mono<ExecResult> read(String workspaceId, String path);

    /** 写入 / 覆盖文件（仅工作空间内，写前加锁 + 快照）。 */
    Mono<ExecResult> write(String workspaceId, String path, String content);

    /** 列出目录。 */
    Mono<ExecResult> list(String workspaceId, String path);

    /** 删除文件或目录（软删除进回收站）。 */
    Mono<ExecResult> delete(String workspaceId, String path);

    /** 文件状态。 */
    Mono<ExecResult> stat(String workspaceId, String path);

    /** 创建目录。 */
    Mono<ExecResult> mkdir(String workspaceId, String path);

    /** 重命名 / 移动。 */
    Mono<ExecResult> rename(String workspaceId, String from, String to);

    /** 执行命令（需全部权限 + ASK，沙箱默认关闭）。 */
    Mono<ExecResult> exec(String workspaceId, String command);
}
