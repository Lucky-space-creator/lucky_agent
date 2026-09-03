package com.lucky.agent.common.util;

import com.lucky.agent.common.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PathUtil realpath 越界防护单元测试。
 */
class PathUtilTest {

    @Test
    void testResolveWithin_NormalPath() {
        Path result = PathUtil.resolveWithin(Paths.get("C:/ws"), "src/main/java");
        assertEquals(Paths.get("C:/ws/src/main/java").normalize(), result);
    }

    @Test
    void testResolveWithin_DotDotEscapes_Throws() {
        assertThrows(AgentException.class,
                () -> PathUtil.resolveWithin(Paths.get("C:/ws"), "../etc/passwd"));
    }

    @Test
    void testResolveWithin_AbsoluteEscape_Throws() {
        assertThrows(AgentException.class,
                () -> PathUtil.resolveWithin(Paths.get("C:/ws"), "C:/other/file.txt"));
    }

    @Test
    void testIsWithin_True() {
        boolean within = PathUtil.isWithin(Paths.get("C:/ws"), Paths.get("C:/ws/sub/file.txt"));
        assertEquals(true, within);
    }
}
