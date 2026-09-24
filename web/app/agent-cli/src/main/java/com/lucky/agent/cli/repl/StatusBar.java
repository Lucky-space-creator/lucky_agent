package com.lucky.agent.cli.repl;

import com.lucky.agent.cli.channel.OutputSink;

/**
 * 状态行：`model │ mode │ perm │ session │ tokens │ 耗时`。
 *
 * <p><b>为什么不是「底部常驻刷新条」</b>：那需要保存/恢复光标位置并独占一行，
 * 而正文是流式逐字输出的（{@code content_delta}），两者在同一行上互相覆盖，
 * 在非 ANSI 终端或重定向场景下会留下垃圾字符。本实现把状态行落在<b>轮次边界</b>
 * （本轮开始前一条、结束后一条），信息量完整、不干扰流式正文、在任何终端上都不会错位。
 * 这是有意识的取舍，不是未实现。</p>
 *
 * <p>权限档只读展示：CLI <b>没有</b> 提升权限的入口（决策 D2，权限由执行臂硬边界强制）。
 * 因此这里展示的 `perm` 只是把当前工作空间的真实级别告诉用户，不提供任何修改手段。</p>
 */
public final class StatusBar {

    /** 空值占位，避免出现 `null` 这种噪音。 */
    private static final String NA = "-";

    private StatusBar() {
    }

    /**
     * 组装状态行文本。
     *
     * @param model        当前模型名
     * @param mode         运行模式（ACT / PLAN / ASK）
     * @param perm         工作空间权限标签
     * @param sessionShort 会话 ID 前 8 位
     * @param turnTokens   本轮 token
     * @param totalTokens  本进程累计 token
     * @param elapsedMs    本轮耗时（毫秒；&lt;0 表示不展示）
     */
    public static String format(String model, String mode, String perm, String sessionShort,
                                long turnTokens, long totalTokens, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(orDash(model));
        sb.append(" │ mode=").append(orDash(mode));
        sb.append(" │ perm=").append(orDash(perm));
        sb.append(" │ session=").append(orDash(sessionShort));
        sb.append(" │ tokens=").append(turnTokens).append("/").append(totalTokens);
        if (elapsedMs >= 0) {
            sb.append(" │ ").append(formatDuration(elapsedMs));
        }
        return sb.toString();
    }

    /** 打印状态行（暗色，不抢正文注意力）。 */
    public static void print(OutputSink out, String line) {
        out.println(out.theme().dim(line));
    }

    /** 毫秒 → 人类可读（`820ms` / `3.4s` / `1m12s`）。 */
    public static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        long totalSec = ms / 1000;
        return (totalSec / 60) + "m" + (totalSec % 60) + "s";
    }

    /** 会话 ID 短形式（前 8 位）。 */
    public static String shortId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return NA;
        }
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? NA : v;
    }
}
