package com.lucky.agent.permission.support.rules;

import com.lucky.agent.common.contract.PermissionRule;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 路径规则匹配器（glob/regex + 锚定语义）。
 *
 * <p>路径锚定语义固定：{@code //abs} 文件系统绝对路径、{@code home} 用户目录、
 * {@code project} 工作区根、{@code relative} 当前目录；{@code /project-root} 不会被误当成文件系统根。
 * glob 以字符串拼接解析并转正则，规避平台路径分隔符差异（Windows {@code \} / Unix {@code /}）。</p>
 */
public class PathRuleMatcher {

    /**
     * 判断规则是否命中给定绝对路径。
     *
     * @param rule          规则
     * @param workspaceRoot 工作区根（project 锚定用）
     * @param absPath       待判定绝对路径
     * @return true 命中
     */
    public boolean matches(PermissionRule rule, Path workspaceRoot, Path absPath) {
        if (rule.matcher() == null || rule.matcher().pattern() == null) {
            return false;
        }
        String pattern = rule.matcher().pattern();
        PermissionRule.Anchor anchor = PermissionRule.Anchor.fromCode(rule.matcher().anchor());
        String resolvedPattern = switch (anchor) {
            case ABS -> pattern;
            case PROJECT -> toSlash(workspaceRoot.toString()) + "/" + stripLeadingSlash(pattern);
            case HOME -> toSlash(System.getProperty("user.home")) + "/" + stripLeadingSlash(pattern);
            case RELATIVE -> pattern;
        };
        return match(resolvedPattern, toSlash(absPath.toString()));
    }

    private boolean match(String pattern, String target) {
        if (pattern.startsWith("regex:")) {
            return Pattern.compile(pattern.substring("regex:".length()), Pattern.CASE_INSENSITIVE)
                    .matcher(target).find();
        }
        return Pattern.compile(globToRegex(pattern), Pattern.CASE_INSENSITIVE).matcher(target).matches();
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '[':
                    int close = glob.indexOf(']', i);
                    if (close > i) {
                        sb.append(glob, i, close + 1);
                        i = close;
                    } else {
                        sb.append("\\[");
                    }
                    break;
                default:
                    if (".+()^$|\\{}".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }

    private String stripLeadingSlash(String pattern) {
        return pattern.replaceFirst("^[/\\\\]+", "");
    }

    private String toSlash(String path) {
        return path.replace('\\', '/');
    }
}
