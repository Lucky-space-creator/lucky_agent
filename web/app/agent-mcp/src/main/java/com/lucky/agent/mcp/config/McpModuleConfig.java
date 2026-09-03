package com.lucky.agent.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.api.ToolSource;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.mcp.McpToolSource;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.auth.UserAuthIsolator;
import com.lucky.agent.mcp.connect.ConnectionManager;
import com.lucky.agent.mcp.connect.McpHealthProbe;
import com.lucky.agent.mcp.connect.McpTransportFactory;
import com.lucky.agent.mcp.registry.LocalMcpRegistry;
import com.lucky.agent.mcp.registry.McpLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * agent-mcp 配置：加载器 + 注册中心 + 授权隔离 + 传输工厂 + 连接管理 + 探活 + 工具来源装配。
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@EnableScheduling
public class McpModuleConfig {

    /** 磁盘加载器（扫描 {@code .mcp/*.json}）。 */
    @Bean
    public McpLoader mcpLoader(WorkspaceDirs dirs, McpProperties properties, ObjectMapper objectMapper) {
        return new McpLoader(dirs, properties, objectMapper);
    }

    /** 本地注册中心（启动即加载，含定时扫描）。 */
    @Bean
    public LocalMcpRegistry mcpRegistry(McpLoader loader, WorkspaceDirs dirs, ObjectMapper objectMapper,
                                        McpProperties properties) {
        LocalMcpRegistry registry = new LocalMcpRegistry(loader, dirs, objectMapper, properties);
        registry.init();
        return registry;
    }

    /** 用户授权隔离（启动加载授权集合）。 */
    @Bean
    public UserAuthIsolator userAuthIsolator(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        UserAuthIsolator isolator = new UserAuthIsolator(dirs, objectMapper);
        isolator.load();
        return isolator;
    }

    /** 传输工厂（stdio/HTTP）。 */
    @Bean
    public McpTransportFactory mcpTransportFactory(McpProperties properties, ObjectMapper objectMapper) {
        return new McpTransportFactory(properties, objectMapper);
    }

    /** 连接管理器（懒连/重连/心跳，实现 McpConnector 契约）。 */
    @Bean
    public ConnectionManager connectionManager(McpRegistry registry, McpTransportFactory transportFactory,
                                               McpProperties properties) {
        return new ConnectionManager(registry, transportFactory, properties);
    }

    /** MCP 健康探活（心跳超时重连；命名避开 agent-model 的 HealthProbe 组件）。 */
    @Bean
    public McpHealthProbe mcpHealthProbe(McpRegistry registry, McpConnector connector, UserAuthIsolator auth) {
        return new McpHealthProbe(registry, connector, auth);
    }

    /** MCP 工具来源（注入 ToolGateway）。 */
    @Bean
    public ToolSource mcpToolSource(McpRegistry registry, McpConnector connector, UserAuthIsolator auth) {
        return new McpToolSource(registry, connector, auth);
    }
}
