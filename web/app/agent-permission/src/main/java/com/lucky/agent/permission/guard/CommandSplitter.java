package com.lucky.agent.permission.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合命令拆分（D14 P2：复合命令拆分）。
 *
 * <p>将 {@code &&} / {@code ||} / {@code ;} / {@code |} / 换行 / {@code &} 连接的复合命令拆分为
 * 单条命令，逐条交由权限规则链裁决；任一条被 deny 即整体 deny。空命令与注释（{@code #} 开头）跳过。</p>
 */
public class CommandSplitter {

    /**
     * 拆分复合命令为单条命令列表。
     *
     * @param command 原始命令文本
     * @return 非空单条命令列表（已 trim，去重空白）
     */
    public List<String> split(String command) {
        if (command == null || command.isBlank()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        // 先按换行拆，再按 shell 连接符拆
        String[] lines = command.split("[\r\n]+");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                continue;
            }
            for (String part : line.split("\\|\\||\\|&&||\\|;||\\||\\|&|\\|\\n")) {
                String p = part.trim();
                if (!p.isEmpty() && !p.startsWith("#")) {
                    result.add(p);
                }
            }
        }
        return result;
    }
}
