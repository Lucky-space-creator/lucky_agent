package com.lucky.agent.cli.input;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.common.dto.ExecResult;
import com.lucky.agent.executor.api.FileService;

import java.time.Duration;
import java.util.List;

/**
 * {@code @} 前缀：把单个文件的内容注入本轮上下文。
 *
 * <p>为什么需要它：终端里描述「改一下 D:\proj\src\Main.java 第 30 行的逻辑」既啰嗦又容易指错文件。
 * 引用式输入让用户把文件本身递进去，模型看到的是真实内容而不是用户的转述 —— 这是主流 CLI 里
 * 性价比最高的一个输入增强。</p>
 *
 * <p><b>读取也走执行臂</b>：用 {@link FileService#read} 而不是 {@code Files.readString}。
 * 读虽属只读操作，但「所有文件操作必须经执行臂硬边界校验」是本项目的硬约束（决策 D2），
 * 且这才是唯一能保证「CLI 读到的路径与 Agent 能读到的路径完全同一套判定」的办法。</p>
 *
 * <p><b>体积上限</b>：超过 {@value #MAX_CHARS} 字符即截断并明确告知。不截断的后果是把整份
 * 大文件塞进上下文，既可能超出模型窗口，也会把本轮 token 预算一次性烧光。</p>
 */
public final class FileReference {

    /** 注入内容上限（字符）。 */
    public static final int MAX_CHARS = 64 * 1024;

    /** 前缀字符。 */
    public static final char PREFIX = '@';

    /** 读取等待上限。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private FileReference() {
    }

    /** 是否为本前缀的输入行。 */
    public static boolean matches(String line) {
        return line != null && !line.isEmpty() && line.charAt(0) == PREFIX;
    }

    /**
     * 展开引用：把 {@code @路径 说明} 变成「说明 + 文件内容块」的完整提示词。
     *
     * @return 展开后的提示词；失败时返回 {@code null}（调用方应放弃本轮提交）
     */
    public static String expand(String line, String workspaceId, FileService fileService, OutputSink out) {
        String body = line.substring(1).trim();
        if (body.isEmpty()) {
            out.println(out.theme().yellow("用法：@<文件路径> [补充说明]，例如 @README.md 帮我重写简介"));
            return null;
        }
        String path;
        String note;
        int sp = indexOfWhitespace(body);
        if (sp < 0) {
            path = body;
            note = "";
        } else {
            path = body.substring(0, sp);
            note = body.substring(sp).trim();
        }
        if (fileService == null) {
            out.println(out.theme().red("执行臂不可用，无法读取引用文件。"));
            return null;
        }
        ExecResult r;
        try {
            r = fileService.read(workspaceId, path).block(TIMEOUT);
        } catch (Exception e) {
            out.println(out.theme().red("读取失败：" + e.getMessage()));
            return null;
        }
        if (r == null) {
            out.println(out.theme().red("读取超时（" + TIMEOUT.toSeconds() + "s）：" + path));
            return null;
        }
        if (!r.ok()) {
            out.println(out.theme().red("读取失败：" + (r.error() == null ? "未知原因" : r.error())));
            return null;
        }
        String content = r.content() == null ? "" : r.content();
        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS);
        }
        out.println(out.theme().dim("已引用 " + path + "（" + r.size() + " 字节"
                + (truncated ? "，内容超过 " + MAX_CHARS + " 字符已截断" : "") + "）"));

        StringBuilder sb = new StringBuilder();
        sb.append(note.isEmpty() ? "请阅读以下文件内容并据此处理。" : note).append('\n');
        sb.append("\n【引用文件 ").append(path).append("】\n```\n").append(content).append("\n```");
        if (truncated) {
            sb.append("\n（注：文件内容过长，以上仅为前 ").append(MAX_CHARS).append(" 字符。）");
        }
        return sb.toString();
    }

    /** 找到第一个空白字符的位置（用于拆分路径与补充说明）。 */
    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 供补全使用：把候选路径补齐为 {@code @path} 形式。 */
    public static List<String> decorate(List<String> paths) {
        return paths.stream().map(p -> PREFIX + p).toList();
    }
}
