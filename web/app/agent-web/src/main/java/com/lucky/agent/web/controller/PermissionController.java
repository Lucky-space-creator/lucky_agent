package com.lucky.agent.web.controller;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.contract.PermissionRule;
import com.lucky.agent.permission.api.PermissionService;
import com.lucky.agent.workspace.api.WorkspaceConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限配置接口：权限级别 + deny/ask/allow 规则集。
 */
@RestController
@RequestMapping("/api/permission")
public class PermissionController {

    private final PermissionService permissionService;
    private final WorkspaceConfig workspaceConfig;

    public PermissionController(PermissionService permissionService, WorkspaceConfig workspaceConfig) {
        this.permissionService = permissionService;
        this.workspaceConfig = workspaceConfig;
    }

    /** 当前规则集。 */
    @GetMapping("/rules")
    public List<PermissionRule> rules() {
        return permissionService.currentRules();
    }

    /** 保存规则集（同步到执行臂本地副本）。 */
    @PutMapping("/rules")
    public Map<String, Integer> saveRules(@RequestBody List<PermissionRule> rules) {
        permissionService.saveRules(rules);
        return Map.of("count", rules == null ? 0 : rules.size());
    }

    /** 用户确认后一次性放行某高危操作（文件浏览器删除/执行）。 */
    @org.springframework.web.bind.annotation.PostMapping("/confirm")
    public Map<String, Boolean> confirm(@RequestBody com.lucky.agent.common.dto.FileOp op) {
        if (op != null && op.opType() != null) {
            permissionService.allowOnce(op);
        }
        return Map.of("ok", true);
    }

    /** 查询工作区权限级别。 */
    @GetMapping("/workspaces/{workspaceId}/level")
    public Map<String, String> level(@PathVariable String workspaceId) {
        PermissionLevel level = workspaceConfig.permissionLevelOf(workspaceId)
                .orElse(PermissionLevel.defaultValue());
        return Map.of("code", level.getCode(), "label", level.getLabel());
    }
}
