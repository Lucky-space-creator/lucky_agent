package com.lucky.agent.workflow;

import com.lucky.agent.workflow.adapter.LlmAdapter;
import com.lucky.agent.workflow.adapter.SandboxAdapter;
import com.lucky.agent.workflow.config.WorkflowAutoConfiguration;
import com.lucky.agent.workflow.engine.WorkflowEngine;
import com.lucky.agent.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置装配测试：验证模块可独立以 Spring 上下文启动，且允许宿主覆盖默认 Bean。
 *
 * <p>注意：{@code WorkflowController} <b>不</b>在此断言——它由
 * {@code @RestController} + 宿主组件扫描装配，而非本配置类以 @Bean 提供。
 * 其路由注册由宿主侧的 WebFlux 集成验证（见 {@code agent-web} 的链路测试）。</p>
 */
class WorkflowAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, WorkflowAutoConfiguration.class));

    @Test
    void shouldWireAllBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkflowService.class);
            assertThat(context).hasSingleBean(WorkflowEngine.class);
            assertThat(context).hasSingleBean(SandboxAdapter.class);
        });
    }

    @Test
    void shouldAllowOverridingDefaultAdapter() {
        runner.withBean("customLlmAdapter", LlmAdapter.class,
                        () -> (LlmAdapter) (prompt, vars) -> "custom-response")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmAdapter.class);
                    assertThat(context.getBean(LlmAdapter.class).complete("hi", Map.of()))
                            .isEqualTo("custom-response");
                });
    }
}
