package com.lucky.agent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lucky Agent Web 启动入口。
 *
 * <p>组件扫描覆盖全模块基础包；agent-web 为纯交互外壳，能力沉淀在后端内核。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lucky.agent")
public class LuckyAgentApplication {

    public static void main(String[] args) {
        // 本机桌面形态：允许后端唤起 AWT 原生目录选择框（Spring Boot 默认 headless=true 会禁用弹框）
        System.setProperty("java.awt.headless", "false");
        SpringApplication.run(LuckyAgentApplication.class, args);
    }
}
