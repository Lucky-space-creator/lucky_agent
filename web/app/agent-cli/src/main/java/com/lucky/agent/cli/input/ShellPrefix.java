package com.lucky.agent.cli.input;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.term.InputReader;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.permission.service.PermissionService;

import java.time.Duration;
import java.util.Locale;
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
 * <p><b>ASK 的处理</b>：{@code FileService#exec} 的返回是同构的（成功 / 失败 / 需确认），
 * 这里对「需用户确认」做一次终端交互 —— 用户同意则用 {@code allowOnce} 放行<b>形状完全相同</b>的
 * {@link FileOp}（{@code EXEC} + {@code workspaceId} + {@code path=null} + {@code args.command}），
 * 然后重试一次。这个形状必须与 {@code LocalFileService#exec} 内部构造的完全一致，否则放行键对不上、
 * 重试会再次被拦。</p>
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
     * @return 是否真的执行了（false 表示用法错误，调用方无需额外处理）
     */
    public static boolean run(String line, String workspaceId, FileService fileService,
                              PermissionService permissionService, OutputSink out, InputReader reader) {
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
            String text = result.content() != null ? result.content()
                    : (result.summary() == null ? "（无输出）" : result.summary());
            out.println(text.stripTrailing());
            return true;
        }

        if (needsApproval(result)) {
            if (reader == null) {
                out.println(out.theme().yellow("该命令需用户确认，当前为非交互环境 → 不执行。"));
                return true;
            }
            out.println(out.theme().danger(" ⚠️ 该命令被判定为高危 ") + " " + command);
            String answer = reader.readLine("是否执行？(y/N): ");
            if (!isYes(answer)) {
                out.println(out.theme().dim("已取消。"));
                return true;
            }
            FileOp op = new FileOp(FileOp.OpType.EXEC).workspaceId(workspaceId)
                    .args(Map.of("command", command));
            permissionService.allowOnce(op);
            ExecResult retry = exec(fileService, workspaceId, command, out);
            if (retry == null) {
                return true;
            }
            if (retry.ok()) {
                String text = retry.content() != null ? retry.content()
                        : (retry.summary() == null ? "（无输出）" : retry.summary());
                out.println(text.stripTrailing());
            } else {
                out.println(out.theme().red("执行失败：" + safeError(retry)));
            }
            return true;
        }

        out.println(out.theme().red("执行失败：" + safeError(result)));
        return true;
    }

    private static ExecResult exec(FileService fileService, String workspaceId, String command, OutputSink out) {
        try {
            return fileService.exec(workspaceId, command).block(TIMEOUT);
        } catch (Exception e) {
            out.println(out.theme().red("执行异常：" + e.getMessage()));
            return null;
        }
    }

    private static boolean needsApproval(ExecResult r) {
        String err = r.error();
        return err != null && (err.contains("需用户确认") || err.toLowerCase(Locale.ROOT).contains("confirm"));
    }

    private static boolean isYes(String answer) {
        String a = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return "y".equals(a) || "yes".equals(a);
    }

    private static String safeError(ExecResult r) {
        return r.error() == null || r.error().isBlank() ? "未知原因" : r.error();
    }
}
