package com.lucky.agent.cli.channel;

/**
 * 终端 ANSI 色板与样式。
 *
 * <p>所有着色都通过本类，便于在 {@code --plain} / 非 TTY / 不支持 ANSI 的终端下<b>一次性整体关闭</b>，
 * 避免颜色码在重定向输出里变成乱码（`--output-format json` 等机器可读输出必须不受影响）。</p>
 */
public final class Theme {

    private final boolean enabled;

    private Theme(boolean enabled) {
        this.enabled = enabled;
    }

    /** 启用 ANSI（交互式 TTY 且未指定 {@code --plain}）。 */
    public static Theme ansi() {
        return new Theme(true);
    }

    /** 纯文本（无任何转义序列）。 */
    public static Theme plain() {
        return new Theme(false);
    }

    public boolean enabled() {
        return enabled;
    }

    public String dim(String text) {
        return wrap("\u001B[2m", text);
    }

    public String green(String text) {
        return wrap("\u001B[32m", text);
    }

    public String red(String text) {
        return wrap("\u001B[31m", text);
    }

    public String yellow(String text) {
        return wrap("\u001B[33m", text);
    }

    public String cyan(String text) {
        return wrap("\u001B[36m", text);
    }

    public String magenta(String text) {
        return wrap("\u001B[35m", text);
    }

    public String bold(String text) {
        return wrap("\u001B[1m", text);
    }

    /** 高风险操作标记：红底加粗，终端不支持时退化为纯文本。 */
    public String danger(String text) {
        return wrap("\u001B[1;41;97m", text);
    }

    private String wrap(String code, String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return code + text + "\u001B[0m";
    }
}
