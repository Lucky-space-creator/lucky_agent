package com.lucky.agent.permission.support.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OS 沙箱约束（D14 P2：OS 沙箱）。
 *
 * <p>本机优先框架不做完整容器隔离，但执行臂在权限裁决前先做一层 OS 级危险语义拦截：
 * 阻止命令触碰系统目录、格式化/清除系统、修改启动项等对宿主有不可逆危害的动作。
 * 命中沙箱红线的命令一律返回 {@code true}（需拦截）。</p>
 *
 * <p><b>为什么不再用「子串黑名单」（台账 #9）</b>：旧实现把整条命令小写后逐条
 * {@code contains} 匹配，两头都错——</p>
 * <ul>
 *   <li><b>漏拦</b>：{@code rm -fr /}、{@code rm  -rf /}（双空格）、{@code rm -rf ~/data}、
 *       {@code rd /s /q C:\}、{@code del *.* /s /q}、{@code bash -c '...'} 全部放行，
 *       等于红线形同虚设；</li>
 *   <li><b>误杀</b>：{@code echo shutdown}、{@code grep mkfs app.log} 被当成危险命令拦下，
 *       正常排障被阻断。</li>
 * </ul>
 *
 * <p>现在改为「归一化 + 命令名判定」：先压空白/去引号，取<b>首个 token</b> 作为命令名，
 * 只对「命令名确实是危险程序」或「rm/rd/del 的危险参数组合 + 危险目标」判红；
 * 参数里出现 shutdown/mkfs 字样不再误杀。同时展开 shell 包装（{@code sh -c}、{@code cmd /c}）
 * 与子命令（{@code $(...)} / 反引号）后递归检测，堵住「包一层就绕过」。</p>
 */
public class OsSandbox {

    /** 首 token（命令名）命中即拦截的不可逆操作程序。 */
    private static final Set<String> FORBIDDEN_EXECUTABLES = Set.of(
            "format", "fdisk", "diskpart", "bcdedit", "wipefs", "shred",
            "shutdown", "reboot", "halt", "poweroff");

    /** 命令名前缀命中即拦截（含变体后缀，如 mkfs.ext4 / mkfs.xfs）。 */
    private static final List<String> FORBIDDEN_EXECUTABLE_PREFIXES = List.of("mkfs");

    /** 多 token 的危险前缀（整条命令归一化后以它开头）。 */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "systemctl disable", "systemctl mask", "crontab -r", "reg delete",
            "netsh advfirewall set allprofiles state off");

    /** 敏感系统路径标记：出现在命令任意位置即拦（读写系统目录都属于越界）。 */
    private static final List<String> FORBIDDEN_PATH_MARKERS = List.of(
            ":/windows/", ":\\windows\\", ":/system32/", ":\\system32\\",
            ":/boot/", ":\\boot\\", ":/etc/passwd", ":/etc/shadow", ":/dev/sd");

    /** 会执行内嵌脚本的 shell 包装器。 */
    private static final Set<String> SHELLS = Set.of(
            "bash", "sh", "zsh", "dash", "ksh", "cmd", "cmd.exe", "powershell", "pwsh", "pwsh.exe");

    /** 递归展开深度上限（防构造性嵌套触发栈溢出）。 */
    private static final int MAX_DEPTH = 3;

    /**
     * 判定命令是否触碰 OS 沙箱红线。
     *
     * @param command 单条命令
     * @return true 表示命中沙箱红线，应拦截
     */
    public boolean violates(String command) {
        return violates(command, 0);
    }

    private boolean violates(String command, int depth) {
        if (command == null || command.isBlank() || depth > MAX_DEPTH) {
            return false;
        }
        String normalized = normalize(command);

        // 复合命令再拆一层：CommandSplitter 已在上游拆过，但 shell 包装（sh -c "a && b"）
        // 与引号内的串联只在这里才看得到，漏拆等于「包一层就绕过」。
        for (String part : normalized.split("&&|\\|\\||;|\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(normalized) && violates(trimmed, depth + 1)) {
                return true;
            }
        }

        // 子命令先展开：echo $(shutdown now) / echo `rm -rf /`
        for (String sub : extractSubcommands(normalized)) {
            if (violates(sub, depth + 1)) {
                return true;
            }
        }

        for (String marker : FORBIDDEN_PATH_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (normalized.startsWith(prefix + " ") || normalized.equals(prefix)) {
                return true;
            }
        }

        String[] tokens = normalized.split(" ");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return false;
        }
        String exe = commandName(tokens[0]);

        // shell 包装：sh -c '<...>' / cmd /c <...> / powershell -Command <...>
        if (SHELLS.contains(exe)) {
            int codeIndex = shellCodeIndex(tokens);
            if (codeIndex > 0 && codeIndex < tokens.length) {
                String inner = String.join(" ", Arrays.copyOfRange(tokens, codeIndex, tokens.length));
                if (violates(inner, depth + 1)) {
                    return true;
                }
            }
        }

        if (FORBIDDEN_EXECUTABLES.contains(exe)) {
            return true;
        }
        for (String prefix : FORBIDDEN_EXECUTABLE_PREFIXES) {
            if (exe.startsWith(prefix)) {
                return true;
            }
        }
        return switch (exe) {
            case "rm" -> dangerousRm(tokens);
            case "rd", "rmdir" -> dangerousRemoveDirectory(tokens);
            case "del", "erase" -> dangerousDel(tokens);
            default -> false;
        };
    }

    /** 压空白、去引号、小写化：让 {@code rm  -rf}、{@code rm -fr}、{@code 'rm -rf /'} 统一可比。 */
    private static String normalize(String command) {
        return command.replaceAll("\\s+", " ")
                .replace("\"", "")
                .replace("'", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /** 取命令名：去掉目录前缀与 .exe/.cmd/.bat 后缀（{@code C:\Windows\System32\format.exe} → format）。 */
    private static String commandName(String token) {
        String name = token.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        for (String suffix : List.of(".exe", ".cmd", ".bat", ".com", ".ps1")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /** 找到 shell 包装里「内嵌脚本起始」的 token 下标；找不到返回 -1。 */
    private static int shellCodeIndex(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("-c") || t.equals("/c") || t.equals("-command") || t.equals("--command")) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 提取 {@code $(...)} 与反引号内的子命令。 */
    private static List<String> extractSubcommands(String command) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = command.indexOf("$(", i)) >= 0) {
            int end = command.indexOf(')', i);
            if (end <= i) {
                break;
            }
            out.add(command.substring(i + 2, end));
            i = end + 1;
        }
        i = 0;
        while ((i = command.indexOf('`', i)) >= 0) {
            int end = command.indexOf('`', i + 1);
            if (end <= i) {
                break;
            }
            out.add(command.substring(i + 1, end));
            i = end + 1;
        }
        return out;
    }

    /** {@code rm}：递归（-r/-R/--recursive）+ 危险目标即拦；不再要求 -f，也不要求参数顺序。 */
    private static boolean dangerousRm(String[] tokens) {
        boolean recursive = false;
        List<String> targets = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("-") && t.length() > 1) {
                if (t.startsWith("--")) {
                    if (t.contains("recursive")) {
                        recursive = true;
                    }
                } else if (t.contains("r")) {
                    recursive = true;
                }
            } else if (!t.isEmpty()) {
                targets.add(t);
            }
        }
        return recursive && targets.stream().anyMatch(OsSandbox::isDangerousTarget);
    }

    /** Windows {@code rd/rmdir}：带 {@code /s} 且目标是盘符根/系统目录即拦。 */
    private static boolean dangerousRemoveDirectory(String[] tokens) {
        boolean recursive = false;
        List<String> targets = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("/") || t.startsWith("-")) {
                if (t.contains("s")) {
                    recursive = true;
                }
            } else if (!t.isEmpty()) {
                targets.add(t);
            }
        }
        return recursive && targets.stream().anyMatch(OsSandbox::isDangerousTarget);
    }

    /** Windows {@code del/erase}：/s 递归删通配或盘符根即拦。 */
    private static boolean dangerousDel(String[] tokens) {
        boolean recursive = false;
        List<String> targets = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("/") || t.startsWith("-")) {
                if (t.contains("s")) {
                    recursive = true;
                }
            } else if (!t.isEmpty()) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        boolean wildcard = tokens.length == 2 && "del".equals(commandName(tokens[0]))
                && targets.get(0).equals("*.*");
        return (recursive || wildcard) && targets.stream().anyMatch(OsSandbox::isDangerousTargetOrWildcard);
    }

    /** 危险目标：文件系统根 / 家目录 / 通配 / 盘符根 / 系统目录。 */
    private static boolean isDangerousTarget(String target) {
        String t = target.replace("\"", "").replace("'", "").trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.equals("/") || t.equals("/*") || t.equals("~") || t.equals("~/*")
                || t.equals("//") || t.equals("\\\\")) {
            return true;
        }
        if (t.startsWith("~/") || t.startsWith("/") && t.chars().filter(c -> c == '/').count() <= 1) {
            // "/xxx" 一级目录：如 /usr、/etc —— 递归删除属破坏性
            return true;
        }
        if (t.equals("$home") || t.startsWith("$home/") || t.startsWith("${home}")) {
            return true;
        }
        if (t.matches("^[a-z]:[\\\\/]?\\*?$")) {
            return true;
        }
        return FORBIDDEN_PATH_MARKERS.stream().anyMatch(t::contains);
    }

    /** 比 {@link #isDangerousTarget} 多认 Windows 全通配。 */
    private static boolean isDangerousTargetOrWildcard(String target) {
        return target.equals("*.*") || target.equals("*") || isDangerousTarget(target);
    }
}
