package com.lucky.agent.cli.repl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 条件选择的序号解析契约（台账 #42）：
 * 用户输入「超长纯数字串」（比如把整段日志粘进选项框）此前会让
 * {@code Integer.parseInt} 抛 {@code NumberFormatException} 穿透整个 REPL 直接退出。
 * 解析必须兜底：解析失败一律按「不在选项范围内」处理，降级为自定义输入。
 */
class ReplLoopOptionIndexTest {

    @Test
    @DisplayName("超长数字串返回 -1（越界语义），不得抛异常")
    void overlongDigitStringIsNotAnExplosion() {
        assertEquals(-1, ReplLoop.parseOptionIndex("123456789012345678901234567890"));
        assertEquals(-1, ReplLoop.parseOptionIndex("99999999999"));
    }

    @Test
    @DisplayName("合法范围内的序号照常解析；负数由调用方的范围检查兜住")
    void normalIndexStillParses() {
        assertEquals(1, ReplLoop.parseOptionIndex("1"));
        assertEquals(12, ReplLoop.parseOptionIndex("12"));
        assertEquals(-3, ReplLoop.parseOptionIndex("-3"), "负数能解析，交给调用方 idx>=1 判定");
    }
}
