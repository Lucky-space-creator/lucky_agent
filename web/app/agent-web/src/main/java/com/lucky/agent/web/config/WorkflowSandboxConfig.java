package com.lucky.agent.web.config;

import com.lucky.agent.executor.api.FileService;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.config.WorkflowProperties;
import com.lucky.agent.web.adapter.WorkflowArmSandboxAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 把工作流「命令节点」收口到本机执行臂。
 *
 * <p>工作流模块默认装配 {@code LocalSandboxAdapter}（自带 {@code ProcessBuilder} 执行），
 * 该实现绕开执行臂的全部边界。本配置以宿主身份提供
 * {@link WorkflowArmSandboxAdapter} 覆盖之，使工作流命令与 Agent 工具调用走同一条
 * 「权限裁决 → realpath 硬边界 → OS 红线 → 断路器」链路（决策 D11/D20/D32）。</p>
 *
 * <p><b>为何显式标注 {@link Primary}：</b>工作流侧默认 Bean 上标的是
 * {@code @ConditionalOnMissingBean(SandboxAdapter.class)}。该条件在「普通 {@code @Configuration}
 * ＋组件扫描」下<b>依赖配置类的处理顺序</b>（仅自动配置才保证在用户配置之后求值）：
 * 若工作流配置先被处理，它会先注册 {@code LocalSandboxAdapter}，此时宿主 Bean 的
 * {@code @ConditionalOnMissingBean} 已无法再回退。标 {@code @Primary} 后，无论顺序如何，
 * 按类型注入 {@code SandboxAdapter} 的位置（{@code WorkflowEngine} / {@code LangGraphWorkflowEngine}）
 * 都确定性地取到本桥接实现，不依赖扫描顺序。</p>
 *
 * <p>默认值未改变：{@code lucky.workflow.sandbox.enabled} 仍为 {@code false}，
 * CODE 节点在用户显式开启前一律被拦截。</p>
 */
@Slf4j
@Configuration
public class WorkflowSandboxConfig {

    @Bean
    @Primary
    public SandboxAdapter workflowArmSandboxAdapter(FileService fileService,
                                                    WorkspaceManager workspaceManager,
                                                    WorkflowProperties properties) {
        WorkflowProperties.Sandbox sandbox = properties.getSandbox();
        String workspaceId = sandbox.getWorkspaceId();
        log.info("工作流命令节点已收口到执行臂：沙箱启用={}，工作空间={}",
                sandbox.isEnabled(),
                workspaceId == null || workspaceId.isBlank() ? "（未配置，回退内置默认工作空间）" : workspaceId);
        return new WorkflowArmSandboxAdapter(fileService, workspaceManager, workspaceId, sandbox.isEnabled());
    }
}