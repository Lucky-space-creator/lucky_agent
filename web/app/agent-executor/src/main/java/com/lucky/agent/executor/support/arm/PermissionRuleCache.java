package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.contract.PermissionRule;

import java.util.List;

/**
 * 执行臂本地权限规则副本（D23）。
 *
 * <p>权限规则同步到执行臂本地，执行臂每次操作重新评估 deny/ask/allow；
 * 框架侧裁决结果仅作提示，不构成最终权限。</p>
 */
public class PermissionRuleCache {

    private volatile List<PermissionRule> rules = List.of();

    /** 同步规则副本。 */
    public void sync(List<PermissionRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 当前规则副本。 */
    public List<PermissionRule> current() {
        return rules;
    }
}
