package com.lucky.agent.cli.repl;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动打印：品牌字标 {@code lucky_agent} + 「工作空间 / 会话 / 终端」回显。
 *
 * <p><b>静态输出，没有动画</b>：字标一次打完，不做逐行揭幕、不做启动节拍。启动打印的职责是
 * 让人一眼看清「Agent 会在哪个目录里干活、用的是哪个会话」，不需要为此占用启动时间。</p>
 *
 * <p><b>颜色只在 ANSI 终端生效</b>：字标按行套蓝色梯度（{@link Theme#color256(int, String)}），
 * 标记与副标套亮蓝。{@code --plain} / 非 TTY（管道、重定向、CI）下 {@link Theme} 整体关闭着色，
 * 同样的文本直出、<b>一个转义序列都不产生</b> —— 否则会污染重定向文件与机器可读输出。</p>
 *
 * <p>本类只做「把内容写进 {@link OutputSink}」：不读终端、不碰内核，故可纯函数式单测
 * （见 {@code BannerTest}）。</p>
 */
public final class Banner {

    /** 品牌名。字标与副标同源，改名只需改这一处。 */
    private static final String BRAND = "lucky_agent";

    /** 字形高度（行）。所有字形必须同高，否则整体会歪。 */
    private static final int ROWS = 6;

    /**
     * 字形宽度（列）。每个字形固定 {@value #ROWS} 行 × {@value #GLYPH_WIDTH} 列，用 {@code █} 拼成。
     *
     * <p><b>为什么是 5 列宽</b>：{@code lucky_agent} 有 11 个字形。若用常见的 ANSI Shadow（8 列/字形）
     * 拼一行，会到 ~99 列 —— 在 80 列终端必然折行，字标就废了。5 列 + 1 空格分隔 = 65 列，
     * 任何终端都放得下。字形改窄的代价是细节变少，故笔画取「实心块」以求小尺寸下仍可辨识。</p>
     */
    private static final int GLYPH_WIDTH = 5;

    /** 品牌字标：由 {@link #compose(String)} 从字形表拼出，各行等宽由构造保证。 */
    static final String[] LOGO = compose(BRAND.toUpperCase());

    /**
     * 字标配色梯度（256 色蓝，自上而下由亮转深）。
     *
     * <p>逐行一个色号、而非逐字符：逐字符会为每个字符各生成一段转义序列（≈3KB 且难读），
     * 逐行只需 6 段，视觉上同样是「一束蓝光」，性价比高得多。</p>
     */
    private static final int[] BLUE_RAMP = {45, 39, 33, 27, 21, 20};

    /** 定位语，与 Web 端一致的对外口径。 */
    private static final String TAGLINE = "本地优先，会话 / 记忆 / 文件只在本机";

    private Banner() {
    }

    /**
     * 打印启动横幅。
     *
     * @param version 展示用版本号
     * @param details 回显行（由调用方用 {@link #detail(Theme, String, String)} 组装）
     */
    public static void print(OutputSink out, String version, List<String> details) {
        Theme t = out.theme();
        for (int i = 0; i < LOGO.length; i++) {
            // 梯度色号与行数未必一一对应（字形表可扩），越界即沿用最深一档，不做静默取模
            out.println(t.color256(BLUE_RAMP[Math.min(i, BLUE_RAMP.length - 1)], LOGO[i]));
        }
        out.println();
        out.println(t.brightBlue(BRAND) + " " + t.bold("CLI")
                + t.dim("   ·   v" + version + "   ·   " + TAGLINE));
        out.println();
        details.forEach(out::println);
        out.println();
    }

    /**
     * 组装一行回显：蓝色标记与标签 + 原样值。
     *
     * <p>值不着色 —— 里面是用户路径与会话 ID，着色既无信息量，还会在浅色背景上降低可读性。</p>
     */
    public static String detail(Theme t, String label, String value) {
        return "  " + t.brightBlue("▸ " + label + "：") + value;
    }

    // ------------------------------------------------------------------ 字形

    /** 把文本拼成 {@value #ROWS} 行等宽字标：逐行取各字形的对应行，之间用一个空格分隔。 */
    static String[] compose(String text) {
        List<String[]> glyphs = new ArrayList<>();
        for (char c : text.toCharArray()) {
            glyphs.add(glyph(c));
        }
        String[] art = new String[ROWS];
        for (int r = 0; r < ROWS; r++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < glyphs.size(); i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(glyphs.get(i)[r]);
            }
            art[r] = line.toString();
        }
        return art;
    }

    /** 单字形：{@value #ROWS} 行 × {@value #GLYPH_WIDTH} 列。未定义字符退化为空白块，不打断启动。 */
    private static String[] glyph(char c) {
        return switch (c) {
            case 'L' -> new String[]{
                    "█    ", "█    ", "█    ", "█    ", "█    ", "█████"};
            case 'U' -> new String[]{
                    "█   █", "█   █", "█   █", "█   █", "█   █", "█████"};
            case 'C' -> new String[]{
                    "█████", "█    ", "█    ", "█    ", "█    ", "█████"};
            case 'K' -> new String[]{
                    "█   █", "█  █ ", "███  ", "█  █ ", "█   █", "█   █"};
            case 'Y' -> new String[]{
                    "█   █", "█   █", " █ █ ", "  █  ", "  █  ", "  █  "};
            case 'A' -> new String[]{
                    "  █  ", " █ █ ", "█   █", "█████", "█   █", "█   █"};
            case 'G' -> new String[]{
                    "█████", "█    ", "█    ", "█  ██", "█   █", "█████"};
            case 'E' -> new String[]{
                    "█████", "█    ", "█    ", "███  ", "█    ", "█████"};
            case 'N' -> new String[]{
                    "█   █", "██  █", "█ █ █", "█  ██", "█   █", "█   █"};
            case 'T' -> new String[]{
                    "█████", "  █  ", "  █  ", "  █  ", "  █  ", "  █  "};
            case '_' -> new String[]{
                    "     ", "     ", "     ", "     ", "     ", "█████"};
            default -> blank();
        };
    }

    /** 未定义字符的占位字幕：整格空白，只保留间距，避免字标错位。 */
    private static String[] blank() {
        String[] rows = new String[ROWS];
        java.util.Arrays.fill(rows, " ".repeat(GLYPH_WIDTH));
        return rows;
    }
}
