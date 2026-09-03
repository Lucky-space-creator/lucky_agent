package com.lucky.agent.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 执行结果（跨模块契约 DTO）。
 *
 * <p>本机模式（LocalTransport，进程内）可携带 {@code content} 供调用方使用；
 * 远端模式（Phase 2 WSS）按「仅元数据」原则只回传路径/大小/状态，不含文件正文。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true, fluent = true)
public class ExecResult {

    @JsonProperty("ok")
    private boolean ok;
    @JsonProperty("opType")
    private FileOp.OpType opType;
    @JsonProperty("requestId")
    private String requestId;
    @JsonProperty("path")
    private String path;
    @JsonProperty("size")
    private long size;
    @JsonProperty("summary")
    private String summary;
    @JsonProperty("content")
    private String content;
    @JsonProperty("entries")
    private List<FileEntry> entries;
    @JsonProperty("error")
    private String error;

    public static ExecResult success(FileOp.OpType opType, String requestId) {
        return new ExecResult().ok(true).opType(opType).requestId(requestId);
    }

    public static ExecResult failure(FileOp.OpType opType, String requestId, String error) {
        return new ExecResult().ok(false).opType(opType).requestId(requestId).error(error);
    }

    /** 目录项（元数据）。 */
    public record FileEntry(String name, String path, boolean dir, long size) {
    }
}
