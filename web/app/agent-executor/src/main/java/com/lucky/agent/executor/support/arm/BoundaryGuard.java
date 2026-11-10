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

    /**
     * 校验 RENAME 的<b>目标</b>路径（台账 #1）。
     *
     * <p>为什么不能复用 {@link #guardWrite}：{@code to} 的语义是「相对源文件所在目录」，
     * 不是相对工作空间根。此前执行臂只校验源路径，{@code to} 为绝对路径
     * （{@code Path.resolve} 遇绝对路径直接采纳）或 {@code ../../..} 时即可把文件
     * 移出工作空间并覆盖任意位置。这里先在源目录上解析出绝对目标，再判定它是否仍
     * 落在工作空间内，最后经 {@link PathUtil#realpathWithin} 阻断符号链接逃逸
     * （目标可能尚不存在，该工具会回溯到最深已存在祖先）。</p>
     *
     * @param workspaceConfig 工作空间配置中心
     * @param workspaceId     工作空间 ID
     * @param sourceAbs       已校验过的源文件绝对路径
     * @param to              目标路径（相对源目录）
     * @return 规范化的绝对目标路径
     * @throws AgentException 参数缺失、目标越界或符号链接逃逸
     */
    public Path guardRenameTarget(WorkspaceConfig workspaceConfig, String workspaceId,
                                  Path sourceAbs, String to) {
        if (to == null || to.isBlank()) {
            throw new AgentException("EXEC_INVALID_PATH", "缺少目标路径参数 to");
        }
        String wsPath = workspaceConfig.physicalPathOf(workspaceId)
                .orElseThrow(() -> new AgentException("WORKSPACE_NOT_FOUND", "工作空间未注册：" + workspaceId));
        Path workspaceRoot = Path.of(wsPath).toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = sourceAbs.getParent().resolve(to).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new AgentException("EXEC_INVALID_PATH", "目标路径不合法：" + to);
        }
        if (!candidate.startsWith(workspaceRoot)) {
            throw new AgentException("EXEC_OUT_OF_BOUNDS", "目标路径越界，禁止移出工作空间：" + to);
        }
        PathUtil.realpathWithin(workspaceRoot, workspaceRoot.relativize(candidate).toString());
        return candidate;
    }
}
