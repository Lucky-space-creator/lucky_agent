package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件操作指令（跨模块契约 DTO）。
 *
 * <p>由 Web/permission 下发，经 {@code ExecutionTransport} 交给本机执行臂执行。
 * 仅传指令与元数据；路径为工作空间内相对路径，绝对越界由执行臂硬边界拦截。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class FileOp {

    @JsonProperty("opType")
    private final OpType opType;
    @JsonProperty("requestId")
    private final String requestId;
    @JsonProperty("workspaceId")
    private String workspaceId;
    @JsonProperty("path")
    private String path;
    @JsonProperty("content")
    private String content;
    @JsonProperty("args")
    private Map<String, Object> args;

    @JsonCreator
    public FileOp(@JsonProperty("opType") OpType opType) {
        this.opType = opType;
        this.requestId = UUID.randomUUID().toString();
    }

    public static FileOp of(OpType opType, String workspaceId, String path) {
        return new FileOp(opType).workspaceId(workspaceId).path(path);
    }

    public FileOp args(Map<String, Object> args) {
        this.args = args == null ? new HashMap<>() : args;
        return this;
    }

    /** 文件操作类型。 */
    public enum OpType {
        /** 读取文件内容。 */
        READ,
        /** 写入 / 覆盖文件内容。 */
        WRITE,
        /** 删除文件或目录（软删除进回收站）。 */
        DELETE,
        /** 列出目录。 */
        LIST,
        /** 创建目录。 */
        MKDIR,
        /** 重命名 / 移动。 */
        RENAME,
        /** 文件状态（大小/类型/时间）。 */
        STAT,
        /** 执行命令（需全部权限）。 */
        EXEC
    }
}
