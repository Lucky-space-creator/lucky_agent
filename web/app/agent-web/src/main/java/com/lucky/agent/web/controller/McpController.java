package com.lucky.agent.web.controller;

import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.mcp.api.McpConnector;
import com.lucky.agent.mcp.api.McpRegistry;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.api.dto.McpTool;
import com.lucky.agent.mcp.support.auth.UserAuthIsolator;
import com.lucky.agent.web.util.PackageImporter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理接口：Server 列表/增删改/启停/授权隔离/连接控制/工具查看/本地上传。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpRegistry registry;
    private final McpConnector connector;
    private final UserAuthIsolator auth;
    private final WorkspaceDirs dirs;

    public McpController(McpRegistry registry, McpConnector connector, UserAuthIsolator auth, WorkspaceDirs dirs) {
        this.registry = registry;
        this.connector = connector;
        this.auth = auth;
        this.dirs = dirs;
    }

    /** 列出全部 MCP Server（附运行状态与授权标记）。 */
    @GetMapping
    public List<McpServerView> list() {
        List<McpServerView> views = new ArrayList<>();
        for (McpServerDef def : registry.list()) {
            views.add(McpServerView.from(def, connector.status(def.id()), auth.isAuthorized(def.id())));
        }
        return views;
    }

    /** 保存（新增或覆盖）一个 MCP Server 配置。 */
    @PostMapping
    public McpServerDef save(@RequestBody McpServerDef body) {
        return registry.save(body);
    }

    /**
     * 本地上传 MCP Server 包（{@code .zip} 压缩包或多 {@code meta.json} 单文件），解包导入并立即生效。
     * 上传文件为阻塞 IO，在 boundedElastic 线程执行，避免占用 Reactor 事件循环线程。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<McpServerDef> upload(@RequestPart("file") MultipartFile file) {
        return Mono.fromCallable(() -> doUpload(file)).subscribeOn(Schedulers.boundedElastic());
    }

    private McpServerDef doUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AgentException("IMPORT_EMPTY", "请选择要上传的 MCP 包（.zip 或 meta.json）");
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(dirs.tmpDir(), "mcp-upload-");
            Path contentRoot = PackageImporter.unpack(file, tempDir);
            return registry.importFrom(contentRoot);
        } catch (IOException e) {
            throw new AgentException("IMPORT_IO_ERROR", "上传文件处理失败", e);
        } finally {
            PackageImporter.deleteQuietly(tempDir);
        }
    }

    /** 删除用户自建 MCP Server 配置。 */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> remove(@PathVariable String id) {
        return Map.of("removed", registry.remove(id));
    }

    /** 启用/禁用某 MCP Server。 */
    @PostMapping("/{id}/enabled")
    public Map<String, Boolean> setEnabled(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        registry.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("enabled", registry.find(id).map(McpServerDef::enabled).orElse(false));
    }

    /** 手动触发重载（磁盘扫描）。 */
    @PostMapping("/reload")
    public Map<String, Integer> reload() {
        registry.reload();
        return Map.of("count", registry.list().size());
    }

    /** 授权连接某 MCP Server（授权后其 Tool 才注入网关）。 */
    @PostMapping("/{id}/authorize")
    public Map<String, Boolean> authorize(@PathVariable String id) {
        auth.authorize(id);
        return Map.of("authorized", auth.isAuthorized(id));
    }

    /** 撤销授权（对应 Tool 从下一轮网关注销）。 */
    @PostMapping("/{id}/revoke")
    public Map<String, Boolean> revoke(@PathVariable String id) {
        return Map.of("revoked", auth.revoke(id));
    }

    /** 已授权 Server 列表。 */
    @GetMapping("/auth")
    public Map<String, List<String>> authorized() {
        return Map.of("authorized", auth.authorizedList());
    }

    /** 某 Server 运行状态。 */
    @GetMapping("/{id}/status")
    public Map<String, String> status(@PathVariable String id) {
        return Map.of("status", connector.status(id));
    }

    /** 建立连接（懒连；失败以状态体现不抛错）。 */
    @PostMapping("/{id}/connect")
    public Map<String, String> connect(@PathVariable String id) {
        connector.connect(id);
        return Map.of("status", connector.status(id));
    }

    /** 断开重连。 */
    @PostMapping("/{id}/reconnect")
    public Map<String, String> reconnect(@PathVariable String id) {
        connector.reconnect(id);
        return Map.of("status", connector.status(id));
    }

    /** 某 Server 的 Tool 列表（未连接时按需连接）。 */
    @GetMapping("/{id}/tools")
    public List<McpTool> tools(@PathVariable String id) {
        return connector.listTools(id);
    }

    /** MCP Server 视图（配置 + 运行状态 + 授权标记）。 */
    public record McpServerView(
            String id,
            String name,
            String description,
            String type,
            String command,
            List<String> args,
            String endpointUrl,
            boolean enabled,
            String status,
            boolean authorized) {

        static McpServerView from(McpServerDef def, String status, boolean authorized) {
            return new McpServerView(def.id(), def.name(), def.description(), def.type(),
                    def.command(), def.args(), def.endpointUrl(), def.enabled(), status, authorized);
        }
    }
}
