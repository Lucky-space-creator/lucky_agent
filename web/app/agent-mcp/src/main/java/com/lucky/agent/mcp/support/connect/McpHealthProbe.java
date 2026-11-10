package com.lucky.agent.mcp.support.connect;

import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.support.auth.UserAuthIsolator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * MCP 健康探活：周期对已授权的启用 MCP 做心跳与重连。
 *
 * <p>仅探活已授权 Server，未授权 Server 不发起连接（授权隔离不产生隐式副作用）；
 * 心跳失败自动断开重连，连接失败标记 ERROR 待下次重试。</p>
 *
 * <p>命名带 {@code Mcp} 前缀以避免与 agent-model 的 {@code HealthProbe} 组件重名。</p>
 */
@Slf4j
public class McpHealthProbe {

    private final McpRegistry registry;
    private final McpConnector connector;
    private final UserAuthIsolator auth;

    public McpHealthProbe(McpRegistry registry, McpConnector connector, UserAuthIsolator auth) {
        this.registry = registry;
        this.connector = connector;
        this.auth = auth;
    }

    /**
     * 探活定时任务（mcp.health-probe-sec，默认 60 秒）。
     *
     * <p>注意单位：{@code fixedDelayString} 的裸数字按<b>毫秒</b>解析，配置项语义是「秒」，
     * 必须带 {@code s} 后缀换算 —— 此前写 {@code :60000} 语义还行，但用户配 {@code 60}
     * 会变成 60ms 的探活风暴；与台账 #21 的 {@code model.health-probe-sec} 同一坑。</p>
     */
    @Scheduled(fixedDelayString = "${mcp.health-probe-sec:60}s")
    public void probe() {
        for (McpServerDef def : registry.listEnabled()) {
            if (!auth.isAuthorized(def.id())) {
                continue;
            }
            try {
                if (connector.isConnected(def.id())) {
                    if (!connector.ping(def.id())) {
                        log.warn("MCP 心跳失败，断开重连：{}", def.id());
                        connector.reconnect(def.id());
                    }
                } else {
                    connector.connect(def.id());
                }
            } catch (Exception e) {
                // 只记摘要，不打印堆栈：MCP 未安装/未启动时每轮探活都会失败，堆栈刷终端无信息量
                log.warn("MCP 探活异常：{}（{}）", def.id(), e.getMessage());
                log.debug("MCP 探活异常详情：{}", def.id(), e);
            }
        }
    }
}
