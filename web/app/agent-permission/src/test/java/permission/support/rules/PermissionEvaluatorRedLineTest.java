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

    @Test
    void writeOverExistingFileAndRenameAreDangerous() {
        // 台账 #7：类注释写「删除/执行/覆盖返回 HIGH」，实现却只标 DELETE/EXEC，
        // 导致「修改文件」档下覆盖 2000 行源文件直接 ALLOW。
        DangerousOpDetector detector = new DangerousOpDetector();

        com.lucky.agent.common.dto.FileOp write = new com.lucky.agent.common.dto.FileOp(
                com.lucky.agent.common.dto.FileOp.OpType.WRITE).workspaceId("w").path("Main.java");
        assertTrue(detector.isDangerous(write, true), "覆盖已有文件必须判高风险");
        assertFalse(detector.isDangerous(write, false), "新建文件仍是低风险");

        com.lucky.agent.common.dto.FileOp rename = new com.lucky.agent.common.dto.FileOp(
                com.lucky.agent.common.dto.FileOp.OpType.RENAME).workspaceId("w").path("a.txt");
        assertTrue(detector.isDangerous(rename, false), "RENAME 会覆盖目标，必须判高风险");

        // 保守重载（目标存在性未知）按「可能覆盖」处理
        assertTrue(detector.isDangerous(write));
    }
}
