package com.lucky.agent.cli.repl;

import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.Theme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动打印。
 *
 * <p>守住四条：<b>字标等宽且不折行</b>（字宽不一致整体会歪；超 80 列会在窄终端折行）、
 * <b>纯文本零转义</b>（重定向/管道里不能混入控制序列）、<b>ANSI 下确有蓝色</b>、
 * <b>没有动画</b>（不得出现 {@code \r} 覆写与 {@code ESC[2K} 擦行）。</p>
 */
class BannerTest {

    @Test
    @DisplayName("字标：6 行等宽、宽 65 列（80 列终端不折行）")
    void logoIsEqualWidthAndFitsEightyColumns() {
        assertEquals(6, Banner.LOGO.length, "字标应为 6 行");
        int width = Banner.LOGO[0].length();
        for (int i = 1; i < Banner.LOGO.length; i++) {
            assertEquals(width, Banner.LOGO[i].length(), "第 " + i + " 行宽度与首行不一致，字标会歪");
        }
        assertEquals(65, width, "字标宽度应为 11 字形 × 5 列 + 10 间隔 = 65");
        assertTrue(width <= 80, "字标宽 " + width + " 列，80 列终端会折行");
    }

    @Test
    @DisplayName("字形只由实心块与空格组成（不混入控制字符）")
    void glyphsUseBlocksOnly() {
        for (String row : Banner.LOGO) {
            assertTrue(row.chars().allMatch(c -> c == '█' || c == ' '),
                    "字标出现非方块/空格字符：" + row);
        }
    }

    @Test
    @DisplayName("纯文本模式：无任何转义序列，且信息一字不少")
    void plainThemeHasNoEscapeAndKeepsInfo() {
        String text = render(Theme.plain(), List.of(
                Banner.detail(Theme.plain(), "工作空间", "SpringBlade · 权限 全部权限"),
                Banner.detail(Theme.plain(), "会话", "a4a483e2")));

        assertFalse(text.contains("\u001B"), "纯文本模式不应含 ANSI 转义：\n" + text);
        assertTrue(text.contains("lucky_agent"), "应含品牌名");
        assertTrue(text.contains("v9.9.9"), "应含版本号");
        assertTrue(text.contains("本地优先"), "应含定位语");
        assertTrue(text.contains("SpringBlade · 权限 全部权限"), "应回显工作空间");
        assertTrue(text.contains("a4a483e2"), "应回显会话");
        assertTrue(text.contains(Banner.LOGO[0].trim()), "应打印字标（首行）");
    }

    @Test
    @DisplayName("ANSI 模式：蓝色 256 色字标，且不含任何动画痕迹")
    void ansiThemeUsesBlueRampWithoutAnimation() {
        String text = render(Theme.ansi(), List.of(Banner.detail(Theme.ansi(), "会话", "abc")));

        assertTrue(text.contains("\u001B[38;5;45m"), "字标首行应用梯度最亮档蓝色：\n" + text);
        assertTrue(text.contains("\u001B[38;5;20m"), "字标末行应用梯度最深档蓝色");
        assertTrue(text.contains("\u001B[94m"), "标记/标签应用亮蓝");

        // 无动画：不得出现行覆写与擦行序列，也不得有进度条字符。
        // 注意先剥掉平台换行（Windows 上 println 自带 \r\n），只查「孤立的 \r」——那才是覆写。
        String noCrlf = text.replace("\r\n", "\n");
        assertFalse(noCrlf.contains("\r"), "不应出现孤立 \\r 覆写（那是动画痕迹）");
        assertFalse(text.contains("\u001B[2K"), "不应出现 ESC[2K 擦行（那是动画痕迹）");
        assertFalse(text.contains("▰"), "不应出现进度条字符");

        // 去色后正文必须完整：着色不能吃掉内容
        String stripped = text.replaceAll("\u001B\\[[0-9;]*m", "");
        assertTrue(stripped.contains(Banner.LOGO[0].trim()), "去色后仍应含字标首行：\n" + stripped);
    }

    @Test
    @DisplayName("详情行格式：蓝色标记 + 标签 + 原样值")
    void detailLineKeepsRawValue() {
        assertEquals("  ▸ 会话：abc", Banner.detail(Theme.plain(), "会话", "abc"));
    }

    // ------------------------------------------------------------------ 辅助

    private static String render(Theme theme, List<String> details) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        OutputSink sink = OutputSink.of(new PrintStream(buf, true, StandardCharsets.UTF_8), theme);
        Banner.print(sink, "9.9.9", details);
        return buf.toString(StandardCharsets.UTF_8);
    }
}
