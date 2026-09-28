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

    /**
     * 「该操作需用户显式授权」的判定文案（执行臂在裁决结果为 ASK 时以此文案回失败）。
     *
     * <p>存在意义：{@code ExecResult} 只带 {@code ok/error}，没有结构化的「需授权」标志，
     * 消费方（{@code FileOpsToolset}、CLI 的 {@code ShellPrefix}）只能按文案判定。
     * 把文案收敛为唯一常量，可避免生产方与消费方因改字而悄悄失配（失配的后果是
     * 高危命令不再触发授权确认）。</p>
     */
    public static final String ASK_REQUIRED = "高危操作需用户确认";

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
