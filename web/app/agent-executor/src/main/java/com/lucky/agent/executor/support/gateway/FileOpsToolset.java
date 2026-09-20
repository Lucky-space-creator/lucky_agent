package com.lucky.agent.executor.support.gateway;

import com.lucky.agent.common.api.Tool;
import com.lucky.agent.common.api.ToolAnnotations;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.ToolResult;
import com.lucky.agent.executor.api.FileService;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 文件操作工具集（实现 common Tool 契约），供 core 的 ToolGateway 注册进编排引擎。
 *
 * <p>每个工具经 FileService 走「权限裁决 → 执行臂硬边界 → 本机执行」完整链路；
 * 只读工具标记 readOnly（可并行），写工具串行。高危操作被裁决为需确认时返回挂起结果，
 * 由引擎转成 ask 事件等待用户确认（不抛异常）。</p>
 */
public class FileOpsToolset {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String workspaceId;
    private final FileService fileService;

    public FileOpsToolset(String workspaceId, FileService fileService) {
        this.workspaceId = workspaceId;
        this.fileService = fileService;
    }

    /** 所有文件操作工具实例。 */
    public List<Tool> tools() {
        return List.of(
                new ReadTool(), new WriteTool(), new ListTool(), new DeleteTool(),
                new StatTool(), new MkdirTool(), new RenameTool(), new ExecTool());
    }

    private ToolResult toResult(ExecResult r) {
        if (r == null) {
            return ToolResult.error("操作超时");
        }
        if (!r.ok()) {
            if (r.error() != null && r.error().contains("需用户确认")) {
                return ToolResult.suspended(r.error());
            }
            return ToolResult.error(r.error());
        }
        if (r.entries() != null && !r.entries().isEmpty()) {
            String text = r.entries().stream()
                    .map(e -> (e.dir() ? "[目录] " : "[文件] ") + e.name())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("目录为空");
            return ToolResult.ok(text);
        }
        if (r.content() != null) {
            return ToolResult.ok(r.content());
        }
        return ToolResult.ok(r.summary() == null ? "ok" : r.summary());
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private abstract class FileTool implements Tool {
        @Override
        public ToolAnnotations annotations() {
            return ToolAnnotations.writableTool();
        }
    }

    private final class ReadTool extends FileTool {
        @Override public String name() { return "file.read"; }
        @Override public String description() { return "读取工作区内指定文件的内容"; }
        @Override public ToolAnnotations annotations() { return ToolAnnotations.readOnlyTool(); }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.read(workspaceId, str(args, "path")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class WriteTool extends FileTool {
        @Override public String name() { return "file.write"; }
        @Override public String description() { return "写入或覆盖工作区内指定文件的内容"; }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.write(workspaceId, str(args, "path"), str(args, "content")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class ListTool extends FileTool {
        @Override public String name() { return "file.list"; }
        @Override public String description() { return "列出工作区内目录的内容"; }
        @Override public ToolAnnotations annotations() { return ToolAnnotations.readOnlyTool(); }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            String p = str(args, "path");
            ExecResult r = fileService.list(workspaceId, p.isBlank() ? "" : p).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class DeleteTool extends FileTool {
        @Override public String name() { return "file.delete"; }
        @Override public String description() { return "删除工作区内文件或目录（软删除，可 30 天内恢复）"; }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.delete(workspaceId, str(args, "path")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class StatTool extends FileTool {
        @Override public String name() { return "file.stat"; }
        @Override public String description() { return "查看工作区内文件或目录的状态"; }
        @Override public ToolAnnotations annotations() { return ToolAnnotations.readOnlyTool(); }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.stat(workspaceId, str(args, "path")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class MkdirTool extends FileTool {
        @Override public String name() { return "file.mkdir"; }
        @Override public String description() { return "在工作区内创建目录"; }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.mkdir(workspaceId, str(args, "path")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class RenameTool extends FileTool {
        @Override public String name() { return "file.rename"; }
        @Override public String description() { return "在工作区内重命名或移动文件"; }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.rename(workspaceId, str(args, "from"), str(args, "to")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }

    private final class ExecTool extends FileTool {
        @Override public String name() { return "shell.exec"; }
        @Override public String description() { return "在工作空间内执行命令（需全部权限且经用户确认，沙箱默认关闭）"; }
        @Override public Mono<ToolResult> execute(ConversationCtx ctx, Map<String, Object> args) {
            ExecResult r = fileService.exec(workspaceId, str(args, "command")).block(TIMEOUT);
            return Mono.just(toResult(r));
        }
    }
}
