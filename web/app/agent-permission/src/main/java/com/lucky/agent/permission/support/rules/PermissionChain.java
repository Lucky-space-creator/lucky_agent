package com.lucky.agent.permission.support.rules;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.PermissionRule;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * deny/ask/allow 权限规则链（D14）。
 *
 * <p>规则按 {@code priority} 升序执行，DENY 永远胜出（deny-wins）；无规则命中返回 {@code DEFER}，
 * 交由调用方按工作区权限级别默认裁决。任一 deny 立即阻断，不执行后续规则。</p>
 */
public class PermissionChain {

    private final PathRuleMatcher pathRuleMatcher = new PathRuleMatcher();
    private final CommandRuleMatcher commandRuleMatcher = new CommandRuleMatcher();

    /**
     * 路径规则链裁决。
     *
     * @param workspaceRoot 工作区根
     * @param absPath       待判定绝对路径
     * @param rules         规则集（不排序则按 priority 排序）
     * @return 命中规则的裁决结果；无规则命中返回 DEFER
     */
    public PermissionDecision evaluatePath(Path workspaceRoot, Path absPath, List<PermissionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return PermissionDecision.DEFER;
        }
        boolean hasAllow = false;
        boolean hasAsk = false;
        for (PermissionRule rule : rules.stream().sorted(Comparator.comparingInt(PermissionRule::priority)).toList()) {
            if (rule.type() != PermissionRule.RuleType.PATH || !pathRuleMatcher.matches(rule, workspaceRoot, absPath)) {
                continue;
            }
            PermissionDecision decision = toDecision(rule);
            if (decision == PermissionDecision.DENY) {
                return PermissionDecision.DENY;
            }
            if (decision == PermissionDecision.ASK) {
                hasAsk = true;
            }
            if (decision == PermissionDecision.ALLOW) {
                hasAllow = true;
            }
        }
        if (hasAsk) {
            return PermissionDecision.ASK;
        }
        if (hasAllow) {
            return PermissionDecision.ALLOW;
        }
        return PermissionDecision.DEFER;
    }

    /**
     * 命令规则链裁决。
     *
     * @param command 命令文本
     * @param rules   规则集
     * @return 命中规则的裁决结果；无规则命中返回 DEFER
     */
    public PermissionDecision evaluateCommand(String command, List<PermissionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return PermissionDecision.DEFER;
        }
        boolean hasAllow = false;
        boolean hasAsk = false;
        for (PermissionRule rule : rules.stream().sorted(Comparator.comparingInt(PermissionRule::priority)).toList()) {
            if (rule.type() != PermissionRule.RuleType.COMMAND || !commandRuleMatcher.matches(rule, command)) {
                continue;
            }
            PermissionDecision decision = toDecision(rule);
            if (decision == PermissionDecision.DENY) {
                return PermissionDecision.DENY;
            }
            if (decision == PermissionDecision.ASK) {
                hasAsk = true;
            }
            if (decision == PermissionDecision.ALLOW) {
                hasAllow = true;
            }
        }
        if (hasAsk) {
            return PermissionDecision.ASK;
        }
        if (hasAllow) {
            return PermissionDecision.ALLOW;
        }
        return PermissionDecision.DEFER;
    }

    private PermissionDecision toDecision(PermissionRule rule) {
        return switch (rule.action()) {
            case ALLOW -> PermissionDecision.ALLOW;
            case DENY -> PermissionDecision.DENY;
            case ASK -> PermissionDecision.ASK;
        };
    }
}
