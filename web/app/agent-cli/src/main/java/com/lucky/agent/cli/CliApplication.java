package com.lucky.agent.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CLI Agent 启动入口。
 *
 * <p>复用同一内核、配置、记忆与执行臂（D11/D20）：组件扫描覆盖全模块基础包，
 * 业务装配与 Web 通道完全一致，只是交互外壳从浏览器变为终端。不启动 Web 容器。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lucky.agent")
public class CliApplication {

    public static void main(String[] args) {
        // 关闭 Web 容器，仅作为纯 CLI 运行
        System.setProperty("spring.main.web-application-type", "none");
        ConfigurableApplicationContext ctx = SpringApplication.run(CliApplication.class, args);
        ctx.getBean(CliRunner.class).run(args);
    }
}
