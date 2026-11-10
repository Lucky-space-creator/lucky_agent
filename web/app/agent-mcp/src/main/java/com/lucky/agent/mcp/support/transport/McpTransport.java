package com.lucky.agent.mcp.support.transport;

import java.util.Map;

/**
 * MCP 传输抽象：屏蔽 stdio 子进程与 HTTP 端点的差异。
 *
 * <p>传输只负责"发一条 JSON-RPC 请求/通知、收一条匹配 id 的响应"，会话协议
 * （initialize 握手、tools/list、tools/call、ping）由 {@code McpSession} 在传输之上编排。</p>
 */
public interface McpTransport {

    /**
     * 启动传输（stdio 拉子进程 / HTTP 无连接动作）。
     *
     * @throws IllegalStateException 启动失败时抛出，由连接管理器记录为 ERROR
     */
    void start();

    /**
     * 发送请求并等待匹配 id 的响应。
     *
     * @param method    JSON-RPC 方法名
     * @param params    方法参数（可为空）
     * @param timeoutMs 超时毫秒
     * @return 响应；超时/传输中断以 error 响应返回，不抛异常
     */
    JsonRpcResponse request(String method, Map<String, Object> params, long timeoutMs);

    /**
     * 发送通知（无响应）。
     *
     * @param method JSON-RPC 方法名
     * @param params 方法参数（可为空）
     */
    void notify(String method, Map<String, Object> params);

    /** 关闭传输并释放资源（失败仅记日志）。 */
    void close();

    /**
     * 传输是否仍然可用（台账 #38）。
     *
     * <p>stdio 实现检查子进程存活；HTTP 逐请求无长连接，默认恒为 true。
     * 会话层用它区分「已握手但底层已死」——此前子进程退出后 {@code isConnected()}
     * 仍返回 true，状态与实况长期错位，探活都不会触发重连。</p>
     */
    default boolean isAlive() {
        return true;
    }
}
