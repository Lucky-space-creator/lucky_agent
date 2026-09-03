package com.lucky.agent.mcp.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置定义（对应 {@code <frameworkRoot>/.mcp/<id>.json} 单文件）。
 *
 * <p>纯配置数据：启用状态由用户显式开关（{@link #enabled()}），运行时连接状态由
 * 连接管理器维护，不落盘本对象。type 区分两种传输：</p>
 * <ul>
 *     <li>{@link #TYPE_STDIO}：本机子进程 + stdin/stdout JSON-RPC</li>
 *     <li>{@link #TYPE_HTTP}：远端 HTTP/SSE 端点</li>
 * </ul>
 *
 * @param id          MCP 唯一标识（小写中划线，作为工具名前缀）
 * @param name        展示名
 * @param description 描述（LLM 语义理解）
 * @param type        传输类型：{@link #TYPE_STDIO} / {@link #TYPE_HTTP}
 * @param command     stdio 模式可执行命令（HTTP 模式为空）
 * @param args        stdio 模式启动参数（可为空）
 * @param endpointUrl HTTP 模式端点地址（stdio 模式为空）
 * @param env         stdio 模式附加环境变量（可为空）
 * @param enabled     用户是否启用（默认 true）
 */
public record McpServerDef(
        String id,
        String name,
        String description,
        String type,
        String command,
        List<String> args,
        String endpointUrl,
        Map<String, String> env,
        boolean enabled) {

    /** stdio 传输类型。 */
    public static final String TYPE_STDIO = "STDIO";
    /** HTTP 传输类型。 */
    public static final String TYPE_HTTP = "HTTP";

    public McpServerDef {
        if (type == null || type.isBlank()) {
            type = TYPE_STDIO;
        }
        if (args == null) {
            args = new ArrayList<>();
        }
        if (env == null) {
            env = Map.of();
        }
    }

    /** 复制并切换启用状态。 */
    public McpServerDef withEnabled(boolean newEnabled) {
        return new McpServerDef(id, name, description, type, command, args, endpointUrl, env, newEnabled);
    }
}
