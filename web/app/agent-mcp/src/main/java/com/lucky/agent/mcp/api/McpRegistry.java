package com.lucky.agent.mcp.api;

import com.lucky.agent.mcp.api.dto.McpServerDef;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * MCP Server 注册发现契约。
 *
 * <p>维护 MCP Server 配置的生命周期（增删改、启停）与磁盘加载；
 * 授权集合（用户显式"授权连接"）由 {@code UserAuthIsolator} 独立管理。</p>
 */
public interface McpRegistry {

    /** 全部已加载的 MCP Server 定义。 */
    List<McpServerDef> list();

    /** 仅启用的 MCP Server 定义。 */
    List<McpServerDef> listEnabled();

    /** 按 id 查找。 */
    Optional<McpServerDef> find(String id);

    /** 切换启用状态并持久化回 {@code .mcp/<id>.json}。 */
    void setEnabled(String id, boolean enabled);

    /** 保存（新建或覆盖）一个 MCP Server 配置到 {@code .mcp/<id>.json}。 */
    McpServerDef save(McpServerDef def);

    /**
     * 从本地包目录导入 MCP Server（本地上传）：包内须含 {@code meta.json}，整个目录
     * 拷贝进 {@code mcp/<id>/} 并重载。
     */
    McpServerDef importFrom(Path packageDir);

    /** 删除用户自建 MCP Server 配置，返回是否删除成功。 */
    boolean remove(String id);

    /** 重新扫描目录加载（磁盘增删/改动后调用即生效，无需重启）。 */
    void reload();

    /** 注册表版本号（变更自增，供观察者刷新网关注册）。 */
    long revision();
}
