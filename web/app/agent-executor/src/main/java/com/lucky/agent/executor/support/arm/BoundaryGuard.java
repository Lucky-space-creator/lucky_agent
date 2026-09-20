package com.lucky.agent.executor.support.arm;

import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.common.util.PathUtil;
import com.lucky.agent.workspace.api.WorkspaceConfig;

import java.nio.file.Path;

/**
 * 执行臂硬边界守卫：realpath 越界防护（最终裁决）。
 *
 * <p>对每次文件/命令操作先做 {@code realpath} 规范化，阻断 {@code ../}、符号链接逃逸出工作空间；
 * 无论 LLM/框架服务端如何决策，执行臂不越界。</p>
 */
public class BoundaryGuard {

    /**
     * 解析并校验目标路径，确认真实物理路径位于工作空间内。
     *
     * @param workspaceConfig 工作空间配置中心
     * @param workspaceId     工作空间 ID
     * @param userPath        用户提供的路径（工作空间内相对路径）
     * @return 规范化后的工作空间内绝对路径
     * @throws AgentException 工作空间未注册或路径越界
     */
    public Path guard(WorkspaceConfig workspaceConfig, String workspaceId, String userPath) {
        String wsPath = workspaceConfig.physicalPathOf(workspaceId)
                .orElseThrow(() -> new AgentException("WORKSPACE_NOT_FOUND", "工作空间未注册：" + workspaceId));
        Path workspaceRoot = Path.of(wsPath);
        if (userPath == null || userPath.isBlank()) {
            return workspaceRoot;
        }
        return PathUtil.realpathWithin(workspaceRoot, userPath);
    }

    /**
     * 校验待写入路径（目标可能不存在，对最深已存在祖先校验符号链接）。
     *
     * @param workspaceConfig 工作空间配置中心
     * @param workspaceId     工作空间 ID
     * @param userPath        用户提供的路径
     * @return 规范化后的工作空间内绝对路径
     */
    public Path guardWrite(WorkspaceConfig workspaceConfig, String workspaceId, String userPath) {
        String wsPath = workspaceConfig.physicalPathOf(workspaceId)
                .orElseThrow(() -> new AgentException("WORKSPACE_NOT_FOUND", "工作空间未注册：" + workspaceId));
        Path workspaceRoot = Path.of(wsPath);
        return PathUtil.realpathWithin(workspaceRoot, userPath);
    }
}
