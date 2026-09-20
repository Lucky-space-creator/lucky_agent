package com.lucky.agent.permission.service;

import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.contract.Remote;
import com.lucky.agent.common.dto.FileOp;
import com.lucky.agent.common.contract.PermissionRule;

import java.util.List;

/**
 * 权限服务契约：权限级别查询 + deny/ask/allow 规则链裁决。
 *
 * <p>规则链由框架执行（框架侧裁决仅作提示）；执行臂本地持有规则副本重新评估，
 * 为权限与越界的最终裁决者。危险操作识别命中后转 core 的 ASK 流程，不在本模块硬拦截。</p>
 */
@Remote(serviceName = "permission")
public interface PermissionService {

    /**
     * 裁决一次文件操作（框架侧）。
     *
     * @param op          文件操作
     * @param workspaceId 工作空间 ID
     * @return 裁决结果（ALLOW / DENY / ASK / DEFER）
     */
    PermissionDecision evaluateFileOp(FileOp op, String workspaceId);

    /**
     * 裁决一次命令执行（框架侧）。
     *
     * @param command     命令文本
     * @param workspaceId 工作空间 ID
     * @return 裁决结果
     */
    PermissionDecision evaluateCommand(String command, String workspaceId);

    /** 加载当前规则集（来自 {@code <frameworkRoot>/.config/permission-rules.json}）。 */
    List<PermissionRule> loadRules();

    /** 保存规则集（同步到执行臂本地副本）。 */
    void saveRules(List<PermissionRule> rules);

    /** 当前规则集（内存副本，供执行臂本地重新评估）。 */
    List<PermissionRule> currentRules();

    /**
     * 用户确认后一次性放行某高危操作（短 TTL 内该操作不再触发 ASK）。
     *
     * @param op 已确认的高危操作
     */
    void allowOnce(FileOp op);
}
