package permission.support.guard;

import com.lucky.agent.permission.support.guard.OsSandbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OS 沙箱红线回归（台账 #9）。
 *
 * <p>旧实现是「整条命令小写 + 子串匹配」，两头都错：参数顺序/多空格/家目录/Windows 等价命令
 * 全都能绕过；反过来 {@code echo shutdown} 与 {@code grep mkfs} 被误杀。
 * 本测试同时钉死「必须拦住」和「绝不能拦」两侧，避免修漏拦时把误杀又加回来。</p>
 */
class OsSandboxTest {

    private final OsSandbox sandbox = new OsSandbox();

    @Test
    void mustBlockDestructiveVariants() {
        // rm 的参数顺序 / 多空格 / 递归长选项
        assertTrue(sandbox.violates("rm -rf /"), "基线");
        assertTrue(sandbox.violates("rm -fr /"), "参数顺序变体曾可绕过");
        assertTrue(sandbox.violates("rm  -rf  /"), "多空格曾可绕过");
        assertTrue(sandbox.violates("rm -r -f /"), "分离写法");
        assertTrue(sandbox.violates("rm --recursive --force /tmp"), "长选项");
        assertTrue(sandbox.violates("rm -rf ~/data"), "家目录数据");
        assertTrue(sandbox.violates("rm -rf /*"), "根通配");

        // Windows 等价命令
        assertTrue(sandbox.violates("rd /s /q C:\\"), "rd 曾完全放行");
        assertTrue(sandbox.violates("rmdir /s /q C:\\"), "rmdir 同上");
        assertTrue(sandbox.violates("del *.* /s /q"), "del 曾完全放行");
        assertTrue(sandbox.violates("del /f /s /q C:\\*"), "del 盘符根");

        // 不可逆程序（按命令名判定）
        assertTrue(sandbox.violates("format c:"), "格式化");
        assertTrue(sandbox.violates("mkfs.ext4 /dev/sda1"), "含变体后缀");
        assertTrue(sandbox.violates("shutdown now"), "关机");
        assertTrue(sandbox.violates("C:\\Windows\\System32\\format.exe c:"), "带路径与扩展名");
        assertTrue(sandbox.violates("systemctl disable sshd"), "禁用系统服务");
    }

    @Test
    void mustNotBeBypassedByWrapping() {
        assertTrue(sandbox.violates("bash -c 'rm -rf /'"), "shell 包装曾可绕过");
        assertTrue(sandbox.violates("sh -c \"shutdown -h now\""), "sh 包装关机");
        assertTrue(sandbox.violates("cmd /c del /s /q C:\\"), "cmd 包装");
        assertTrue(sandbox.violates("echo $(shutdown now)"), "命令替换");
        assertTrue(sandbox.violates("echo `rm -rf /`"), "反引号");
        assertTrue(sandbox.violates("ls && shutdown now"), "串联");
    }

    @Test
    void mustNotFalsePositiveOnBenignCommands() {
        // 旧实现按子串匹配，这两条曾被误杀
        assertFalse(sandbox.violates("echo shutdown"), "参数里出现 shutdown 不是危险命令");
        assertFalse(sandbox.violates("grep mkfs app.log"), "搜索关键字不是危险命令");
        assertFalse(sandbox.violates("cat /etc/passwd.disabled.bak"), "文件名相似但不是敏感路径");
        // 正常开发命令
        assertFalse(sandbox.violates("git status"));
        assertFalse(sandbox.violates("npm test"));
        assertFalse(sandbox.violates("ls -la"));
        assertFalse(sandbox.violates("rm -rf ./build"), "工作空间内相对路径删除");
        assertFalse(sandbox.violates("rm app.log"), "非递归删除");
        assertFalse(sandbox.violates("del *.tmp"), "非递归通配删除");
        assertFalse(sandbox.violates("rd /s node_modules"), "非盘符根目录");
        assertFalse(sandbox.violates("docker rm -f container1"), "docker rm 不是 rm");
        assertFalse(sandbox.violates(null));
        assertFalse(sandbox.violates(""));
    }
}
