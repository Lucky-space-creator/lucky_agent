package com.lucky.agent.web.workflow;

import com.lucky.agent.core.util.gateway.ToolGateway;
import com.lucky.agent.model.api.ModelRouter;
import com.lucky.agent.web.workflow.adapter.ModelRouterLlmAdapter;
import com.lucky.agent.web.workflow.adapter.ToolGatewayToolAdapter;
import com.lucky.agent.workspace.api.WorkspaceManager;
import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.ToolAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 工作流宿主适配器装配：把 {@code agent-workflow} 的集成缝隙接到宿主真实能力上。
 *
 * <p><b>解决的问题：</b>{@code agent-workflow} 为保证可独立运行，默认注入
 * {@code NoopLlmAdapter}（返回 {@code [noop-llm] <prompt>}）与 {@code NoopToolAdapter}（回显参数）。
 * 未接真实实现时，LLM 节点不调模型、TOOL 节点不调工具 —— 流程「能跑通」但毫无实际产出，
 * 是最容易误导使用者的假成功。本配置补齐这两条链路。</p>
 *
 * <p><b>为何用 {@link Primary} 而不是只依赖 {@code @ConditionalOnMissingBean}：</b>
 * {@code WorkflowAutoConfiguration} 是普通 {@code @Configuration}（由组件扫描装配），
 * 而 {@code @ConditionalOnMissingBean} 的语义依赖「装配顺序」——它在自动配置中可靠，
 * 是因为自动配置保证在用户配置之后执行。<b>两个普通配置类之间没有顺序保证</b>：
 * 若工作流模块先于此配置被处理，Noop 会先注册，随后本配置再注册同类型 Bean，
 * 按类型注入 {@code LlmAdapter} 时将出现两个候选而抛
 * {@code NoSuchUniqueBeanDefinitionException}，直接导致启动失败。
 * 标注 {@code @Primary} 后，无论两者谁先注册，按类型注入都确定性地选中宿主实现，
 * 彻底摆脱顺序依赖。（Noop 仍保留在容器中，供本模块脱离宿主自洽运行。）</p>
 */
@Configuration
public class WorkflowHostAdapterConfig {

    /** LLM 节点 → 宿主模型端点（单步确定性调用，非 REACT 主回环）。 */
    @Bean
    @Primary
    public LlmAdapter hostLlmAdapter(ModelRouter modelRouter) {
        return new ModelRouterLlmAdapter(modelRouter);
    }

    /** TOOL 节点 → 宿主工具网关（复用既有工具集与权限/执行链路）。 */
    @Bean
    @Primary
    public ToolAdapter hostToolAdapter(ToolGateway toolGateway,
                                       ObjectProvider<WorkspaceManager> workspaceManagerProvider) {
        return new ToolGatewayToolAdapter(toolGateway, workspaceManagerProvider);
    }
}
