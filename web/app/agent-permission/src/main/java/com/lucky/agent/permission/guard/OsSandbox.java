package com.lucky.agent.permission.guard;

import java.util.List;
import java.util.Locale;

/**
 * OS 沙箱约束（D14 P2：OS 沙箱）。
 *
 * <p>本机优先框架不做完整容器隔离，但执行臂在权限裁决前先做一层 OS 级危险语义拦截：
 * 阻止命令触碰系统目录、格式化/清除系统、修改启动项等对宿主有不可逆危害的动作。
 * 命中沙箱红线的命令一律返回 {@code true}（需拦截）。</p>
 */
public class OsSandbox {

    /** 命中即视为越界、需拦截的系统级危险模式。 */
    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "format ", "mkfs", "fdisk", "diskpart",
            "rm -rf /", "rm -rf /*", "del /f /s /q", "format c:",
            "shutdown", "reboot", "halt", "poweroff",
            "chkdsk /f", "bcdedit", "crontab -r", "systemctl disable",
            "reg delete", "netsh advfirewall set allprofiles state off",
            ":/windows/", ":/system32/", ":/boot/", ":/etc/passwd", ":/etc/shadow"
    );

    /**
     * 判定命令是否触碰 OS 沙箱红线。
     *
     * @param command 单条命令
     * @return true 表示命中沙箱红线，应拦截
     */
    public boolean violates(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
