package com.lucky.agent.permission.support.rules;

import com.lucky.agent.common.contract.PermissionRule;

import java.util.regex.Pattern;

/**
 * 命令规则匹配器（MVP 为命令文本匹配，Phase 2 补 token 化拆分）。
 */
public class CommandRuleMatcher {

    /**
     * 判断规则是否命中给定命令。
     *
     * @param rule    规则
     * @param command 命令文本
     * @return true 命中
     */
    public boolean matches(PermissionRule rule, String command) {
        if (rule.matcher() == null || rule.matcher().pattern() == null) {
            return false;
        }
        String pattern = rule.matcher().pattern();
        if (pattern.startsWith("regex:")) {
            return Pattern.compile(pattern.substring("regex:".length())).matcher(command).find();
        }
        // 简单 glob：转义通配符后整体匹配
        String regex = globToRegex(pattern);
        return Pattern.compile(regex).matcher(command).find();
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
