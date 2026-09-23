package permission.support.rules;

import com.lucky.agent.permission.support.guard.CommandSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复合命令拆分单测：锁定 {@link CommandSplitter} 不会被拆成单字符（D14 P2 复合命令拆分）。
 */
class CommandSplitterTest {

    private final CommandSplitter splitter = new CommandSplitter();

    @Test
    void splitsSingleCommandAsOnePart() {
        List<String> parts = splitter.split("shutdown now");
        assertEquals(List.of("shutdown now"), parts);
    }

    @Test
    void splitsCompoundByConnectors() {
        assertEquals(List.of("echo hi", "shutdown now"), splitter.split("echo hi && shutdown now"));
        assertEquals(List.of("cd /tmp", "rm -rf /"), splitter.split("cd /tmp; rm -rf /"));
        assertEquals(List.of("a", "b", "c"), splitter.split("a | b || c"));
    }

    @Test
    void emptyAndNullReturnEmpty() {
        assertTrue(splitter.split(null).isEmpty());
        assertTrue(splitter.split("").isEmpty());
        assertTrue(splitter.split("   ").isEmpty());
    }
}
