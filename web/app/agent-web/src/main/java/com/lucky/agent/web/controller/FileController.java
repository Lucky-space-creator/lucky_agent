package com.lucky.agent.web.controller;

import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.permission.service.FileOpForwarder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.Set;

/**
 * 文件操作入口（转发）：前端 → 转发器 → 执行臂本机执行 → 回传元数据。
 *
 * <p>响应体只含路径/大小/状态，不碰文件内容；框架服务端不接触文件正文。</p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    /**
     * UI 可发起的文件操作白名单。
     *
     * <p>刻意排除 {@link FileOp.OpType#EXEC}：文件接口没有「待确认」的交互通道，
     * 而 Web 层（文件树、预览、上传）从不需要执行命令。命令执行只能来自
     * 会话/工作流等带确认链路的入口；放开这里等于给本机任意网页/脚本
     * 一条免确认的命令通道（只需知道 workspaceId）。</p>
     */
    private static final Set<FileOp.OpType> UI_ALLOWED_OPS = EnumSet.of(
            FileOp.OpType.READ,
            FileOp.OpType.WRITE,
            FileOp.OpType.DELETE,
            FileOp.OpType.LIST,
            FileOp.OpType.MKDIR,
            FileOp.OpType.RENAME,
            FileOp.OpType.STAT);

    private final FileOpForwarder fileOpForwarder;

    public FileController(FileOpForwarder fileOpForwarder) {
        this.fileOpForwarder = fileOpForwarder;
    }

    /** 通用文件操作（READ/WRITE/LIST/DELETE/STAT/MKDIR/RENAME；EXEC 不在开放范围）。 */
    @PostMapping("/op")
    public Mono<ExecResult> op(@RequestBody FileOp op) {
        if (op == null || op.opType() == null) {
            return Mono.just(ExecResult.failure(null, null, "缺少操作类型 opType"));
        }
        if (!UI_ALLOWED_OPS.contains(op.opType())) {
            return Mono.just(ExecResult.failure(op.opType(), op.requestId(),
                    "文件接口不开放该操作：" + op.opType() + "（命令执行请通过会话或工作流入口）"));
        }
        return fileOpForwarder.forward(op);
    }

    /** 列目录（文件浏览器）。 */
    @GetMapping("/list")
    public Mono<ExecResult> list(@RequestParam String workspaceId,
                                 @RequestParam(defaultValue = "") String path) {
        return fileOpForwarder.forward(FileOp.of(FileOp.OpType.LIST, workspaceId, path));
    }

    /** 文件状态。 */
    @GetMapping("/stat")
    public Mono<ExecResult> stat(@RequestParam String workspaceId,
                                 @RequestParam String path) {
        return fileOpForwarder.forward(FileOp.of(FileOp.OpType.STAT, workspaceId, path));
    }
}
