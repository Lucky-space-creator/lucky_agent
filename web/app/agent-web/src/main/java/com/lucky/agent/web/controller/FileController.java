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

/**
 * 文件操作入口（转发）：前端 → 转发器 → 执行臂本机执行 → 回传元数据。
 *
 * <p>响应体只含路径/大小/状态，不碰文件内容；框架服务端不接触文件正文。</p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileOpForwarder fileOpForwarder;

    public FileController(FileOpForwarder fileOpForwarder) {
        this.fileOpForwarder = fileOpForwarder;
    }

    /** 通用文件操作（READ/WRITE/LIST/DELETE/STAT/MKDIR/RENAME/EXEC）。 */
    @PostMapping("/op")
    public Mono<ExecResult> op(@RequestBody FileOp op) {
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
