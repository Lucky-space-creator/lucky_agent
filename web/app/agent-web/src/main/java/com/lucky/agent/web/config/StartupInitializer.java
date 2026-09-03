package com.lucky.agent.web.config;

import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.workspace.api.WorkspaceManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动初始化：必备目录由 {@link WorkspaceDirs#init()} 在装配阶段早建，
 * 此处仅注册内置默认工作空间（位于框架根内的 {@code workspace/}，前端不展示、用户无需自建）。
 */

@Slf4j
@Component
public class StartupInitializer implements ApplicationRunner {

    /** 内置默认工作空间名称（用户不可见，仅作兜底落盘位置）。 */
    private static final String BUILTIN_WORKSPACE_NAME = "默认工作空间";

    private final WorkspaceDirs dirs;
    private final WorkspaceManager workspaceManager;

    public StartupInitializer(WorkspaceDirs dirs, WorkspaceManager workspaceManager) {
        this.dirs = dirs;
        this.workspaceManager = workspaceManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            workspaceManager.ensureBuiltinWorkspace(
                    BUILTIN_WORKSPACE_NAME, dirs.defaultWorkspaceRoot(), PermissionLevel.defaultValue());
            log.info("内置默认工作空间就绪：{}", dirs.defaultWorkspaceRoot());
        } catch (Exception e) {
            log.warn("内置默认工作空间注册失败（可稍后在界面创建自定义工作空间）：{}", e.getMessage());
        }
    }
}
