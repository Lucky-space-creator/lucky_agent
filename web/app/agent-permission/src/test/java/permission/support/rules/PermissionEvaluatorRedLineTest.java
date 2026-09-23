package permission.support.rules;

import com.lucky.agent.permission.support.guard.CommandSplitter;
import com.lucky.agent.permission.support.guard.DangerousOpDetector;
import com.lucky.agent.permission.support.guard.OsSandbox;
import com.lucky.agent.permission.support.guard.PermissionCircuitBreaker;
import com.lucky.agent.permission.support.guard.PermissionOverrideStore;
import com.lucky.agent.permission.support.guard.SymlinkResolver;
import com.lucky.agent.permission.support.rules.PermissionChain;
import com.lucky.agent.permission.support.rules.PermissionEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令 OS 沙箱红线检测单测：锁定 EXEC 路径硬边界兜底层的行为，防回归。
 */
class PermissionEvaluatorRedLineTest {

    private final PermissionEvaluator evaluator = new PermissionEvaluator(
            new PermissionChain(), new DangerousOpDetector(), new PermissionOverrideStore(),
            new SymlinkResolver(), new OsSandbox(), new PermissionCircuitBreaker(5));

    @Test
    void benignCommandsDoNotViolate() {
        assertFalse(evaluator.commandViolatesRedLine("ls -la"));
        assertFalse(evaluator.commandViolatesRedLine("npm test"));
        assertFalse(evaluator.commandViolatesRedLine("git status"));
        assertFalse(evaluator.commandViolatesRedLine(null));
        assertFalse(evaluator.commandViolatesRedLine(""));
    }

    @Test
    void redLineCommandsViolate() {
        assertTrue(evaluator.commandViolatesRedLine("shutdown now"));
        assertTrue(evaluator.commandViolatesRedLine("rm -rf /"));
        assertTrue(evaluator.commandViolatesRedLine("format c:"));
        assertTrue(evaluator.commandViolatesRedLine("mkfs.ext4 /dev/sda1"));
    }

    @Test
    void compoundCommandAnyPartViolates() {
        assertTrue(evaluator.commandViolatesRedLine("echo hi && shutdown now"));
        assertTrue(evaluator.commandViolatesRedLine("cd /tmp; rm -rf /"));
    }
}
