package com.lucky.agent.permission.support.rules;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.common.exception.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(PermissionChain.class);

    private final PathRuleMatcher pathRuleMatcher = new PathRuleMatcher();
    private final CommandRuleMatcher commandRuleMatcher = new CommandRuleMatcher();

    /**
     * 路径规则链裁决。
     *
     * <p>规则本身不可解析（如锚点写错）时<b>fail-closed 为 DENY</b>：
     * 一条坏规则宁可让操作被拒并大声报错，也不能静默退化成「空规则」让人以为已封禁（台账 #8）。</p>
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
            if (rule.type() != PermissionRule.RuleType.PATH) {
                continue;
            }
            boolean matched;
            try {
                matched = pathRuleMatcher.matches(rule, workspaceRoot, absPath);
            } catch (AgentException e) {
                log.error("路径规则不可解析，按 DENY 处理：{}", e.getMessage());
                return PermissionDecision.DENY;
            }
            if (!matched) {
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
