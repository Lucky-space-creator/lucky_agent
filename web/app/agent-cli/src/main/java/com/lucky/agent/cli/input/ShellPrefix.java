package com.lucky.agent.cli.input;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code !} 前缀：直接把一行 shell 交给执行臂跑，结果原样打印，<b>不经 LLM</b>。
 *
 * <p><b>为什么仍要走 {@link FileService} 而不是 {@code ProcessBuilder}</b>：本项目的硬约束是
 * 「所有文件/命令操作必须经过本机执行臂的硬边界校验」（决策 D2 / AGENTS.md §三）。CLI 是通道，
 * 通道不许另起一套执行路径 —— 否则用户敲一次 {@code !rm -rf /} 就绕过了权限链与 realpath 边界，
 * 而 Agent 自己跑同一条命令却会被拦。走 {@code FileService#exec} 意味着
 * 「权限裁决 → realpath 硬边界 → 命令拆分 + 沙箱红线 → 断路器」全部生效，与 Agent 调用完全同一条链路。</p>
 *
 * <p><b>ASK 的处理（收口到 {@link ApprovalHandler}）</b>：{@code FileService#exec} 的返回是同构的
 * （成功 / 失败 / 需确认），命中「需确认」（{@link ExecResult#ASK_REQUIRED}）时<b>不再自建一套
 * y/N 提示</b>，而是调用与 Agent 触发的高危确认完全相同的 {@link ApprovalHandler#ask} —— 四档语义
 * （仅本次 / 本会话 / 持久规则 / 拒绝）、提示文案、审计行全部一致。用户同意后，用
 * {@code allowOnce} 放行<b>形状完全相同</b>的 {@link FileOp}（{@code EXEC} + {@code workspaceId}
 * + {@code path=null} + {@code args.command}），然后重试一次；该形状必须与
 * {@code LocalFileService#exec} 内部构造的完全一致，否则放行键对不上、重试会再次被拦。</p>
 */
public final class ShellPrefix {

    /** 单条命令等待上限（执行臂自身也有超时，这里只防终端永久卡住）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    /** 前缀字符。 */
    public static final char PREFIX = '!';

    private ShellPrefix() {
    }

    /** 是否为本前缀的输入行。 */
    public static boolean matches(String line) {
        return line != null && !line.isEmpty() && line.charAt(0) == PREFIX;
    }

    /**
     * 执行一行 {@code !} 命令。
     *
     * @param approval 高危确认裁决器（与 Agent 路径共用同一套四档语义与提示）
     * @return 是否真的执行了（false 表示用法错误，调用方无需额外处理）
     */
    public static boolean run(String line, String workspaceId, FileService fileService,
                              ApprovalHandler approval, PermissionService permissionService, OutputSink out) {
        String command = line.substring(1).trim();
        if (command.isEmpty()) {
            out.println(out.theme().yellow("用法：!<命令>，例如 !git status"));
            return false;
        }
        if (fileService == null) {
            out.println(out.theme().red("执行臂不可用，无法直接执行命令。"));
            return false;
        }
        out.println(out.theme().dim("$ " + command));
        ExecResult result = exec(fileService, workspaceId, command, out);
        if (result == null) {
            return true;
        }

        if (result.ok()) {
            printContent(out, result);
            return true;
        }

        if (needsApproval(result)) {
            if (approval == null) {
                out.println(out.theme().yellow("该命令需用户确认，但当前没有可用的授权交互 → 不执行。"));
                return true;
            }
            ApprovalHandler.Verdict verdict = approval.ask(askOp(command), workspaceId);
            if (!verdict.allowed()) {
                out.println(out.theme().dim("已取消。"));
                return true;
            }
            if (permissionService == null) {
                out.println(out.theme().red("权限服务不可用，无法放行执行。"));
                return true;
            }
            permissionService.allowOnce(allowOnceOp(workspaceId, command));
            ExecResult retry = exec(fileService, workspaceId, command, out);
            if (retry == null) {
                return true;
            }
            if (retry.ok()) {
                printContent(out, retry);
            } else {
                out.println(out.theme().red("执行失败：" + safeError(retry)));
            }
            return true;
        }

        out.println(out.theme().red("执行失败：" + safeError(result)));
        return true;
    }

    private static void printContent(OutputSink out, ExecResult r) {
        String text = r.content() != null ? r.content()
                : (r.summary() == null ? "（无输出）" : r.summary());
        out.println(text.stripTrailing());
    }

    private static ExecResult exec(FileService fileService, String workspaceId, String command, OutputSink out) {
        try {
            return fileService.exec(workspaceId, command).block(TIMEOUT);
        } catch (Exception e) {
            out.println(out.theme().red("执行异常：" + e.getMessage()));
            return null;
        }
    }

    /**
     * 构造「待裁决的操作」——形状与内核 {@code ReactEngine#publishAsk} 一致：
     * EXEC 无 {@code path} 参数时，把 {@code command} 放进 {@code path}，{@code args} 原样带上。
     * 这决定了 {@code a}/{@code r} 两档的匹配键，必须与内核产出的 ask 完全同形。
     */
    private static Map<String, Object> askOp(String command) {
        Map<String, Object> op = new HashMap<>();
        op.put("opType", "EXEC");
        op.put("path", command);
        op.put("args", Map.of("command", command));
        return op;
    }

    /** 放行用的 op：形状必须与 {@code LocalFileService#exec} 构造的完全一致（path=null）。 */
    private static FileOp allowOnceOp(String workspaceId, String command) {
        return new FileOp(FileOp.OpType.EXEC).workspaceId(workspaceId)
                .args(Map.of("command", command));
    }

    private static boolean needsApproval(ExecResult r) {
        String err = r.error();
        return err != null && err.contains(ExecResult.ASK_REQUIRED);
    }

    private static String safeError(ExecResult r) {
        return r.error() == null || r.error().isBlank() ? "未知原因" : r.error();
    }
}
