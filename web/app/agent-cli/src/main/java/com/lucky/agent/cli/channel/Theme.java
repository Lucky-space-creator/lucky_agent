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

    /**
     * 亮蓝（94）：品牌主色的可读档。
     *
     * <p>字标要用 256 色渐变（见 {@link #color256(int, String)}），但<b>正文标记与标签</b>不适合
     * 写死 256 色号 —— 那是按深色背景挑的值，在浅色背景（白底终端）上偏暗。亮蓝由终端自行映射，
     * 深浅背景都可读，故「品牌感的文字」用它，而「装饰性渐变」才用固定色号。</p>
     */
    public String brightBlue(String text) {
        return wrap("\u001B[94m", text);
    }

    /**
     * 256 色前景（{@code 38;5;N}）。
     *
     * <p>为什么不用真彩色（{@code 38;2;R;G;B}）：真彩色在旧版 conhost / 部分 CI 终端上会被
     * 原样打印成乱码，而 256 色自 Win10 起即受支持、覆盖所有现代终端。用固定色号换来的稳定性，
     * 远比多几档色阶重要。同 {@link #dim(String)} 一样，纯文本模式下整段直出、不带任何转义。</p>
     *
     * @param index 调色板序号 0-255
     */
    public String color256(int index, String text) {
        return wrap("\u001B[38;5;" + index + "m", text);
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
