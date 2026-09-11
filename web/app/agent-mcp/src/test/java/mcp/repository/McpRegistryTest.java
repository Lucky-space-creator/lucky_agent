package mcp.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.common.exception.AgentException;
import com.lucky.agent.mcp.api.dto.McpServerDef;
import com.lucky.agent.mcp.repository.LocalMcpRegistry;
import com.lucky.agent.mcp.repository.McpLoader;
import com.lucky.agent.mcp.support.auth.UserAuthIsolator;
import com.lucky.agent.mcp.config.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 MCP 注册中心单元测试：磁盘加载、启停持久化、增删配置、授权隔离持久化。
 */
class McpRegistryTest {

    @TempDir
    Path temp;

    private WorkspaceDirs dirs;
    private LocalMcpRegistry registry;
    private McpProperties props;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dirs = new WorkspaceDirs();
        setField(dirs, "frameworkRoot", temp.resolve("fw").toString());
        setField(dirs, "workspaceRoot", temp.resolve("ws").toString());
        dirs.init();
        mapper = new ObjectMapper();
        props = new McpProperties(null, 0, 0, 0, 0);
        registry = new LocalMcpRegistry(new McpLoader(dirs, props, mapper), dirs, mapper, props);
        registry.init();
    }

    @Test
    void testLoadsServers() throws Exception {
        writeServer("github", true);

        registry.reload();

        assertEquals(1, registry.list().size());
        assertTrue(registry.find("github").isPresent());
        assertTrue(registry.find("github").get().enabled());
        assertEquals(McpServerDef.TYPE_STDIO, registry.find("github").get().type());
    }

    @Test
    void testSetEnabledPersistsToFile() throws Exception {
        writeServer("github", true);
        registry.reload();

        registry.setEnabled("github", false);
        assertFalse(registry.find("github").get().enabled());

        // 重新加载后状态保持（落盘生效）
        registry.reload();
        assertFalse(registry.find("github").get().enabled());
        McpServerDef persisted = mapper.readValue(temp.resolve("fw/mcp/github/meta.json").toFile(), McpServerDef.class);
        assertFalse(persisted.enabled());
    }

    @Test
    void testSaveWritesFile() {
        McpServerDef def = new McpServerDef("custom", "自定义", "自定义 MCP", McpServerDef.TYPE_STDIO,
                "echo", List.of("hi"), null, Map.of(), true);

        McpServerDef saved = registry.save(def);

        assertEquals("custom", saved.id());
        assertTrue(registry.find("custom").isPresent());
        assertTrue(Files.isRegularFile(temp.resolve("fw/mcp/custom/meta.json")));
        assertTrue(Files.isDirectory(temp.resolve("fw/mcp/custom")));
    }

    @Test
    void testRemoveDeletesFolder() throws Exception {
        writeServer("github", true);
        registry.reload();

        assertTrue(registry.remove("github"));
        assertFalse(registry.find("github").isPresent());
        assertFalse(Files.exists(temp.resolve("fw/mcp/github")));
    }

    @Test
    void testImportFromPackageCreatesServer() throws Exception {
        Path pkg = temp.resolve("pkg");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("meta.json"), """
                {"id":"imported","name":"导入服务","description":"本地上传","type":"STDIO",
                 "command":"npx","args":["-y","pkg"],"endpointUrl":"","env":{},"enabled":true}
                """, StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("run.js"), "console.log('hi')", StandardCharsets.UTF_8);

        McpServerDef def = registry.importFrom(pkg);

        assertEquals("imported", def.id());
        assertEquals("npx", def.command());
        assertTrue(registry.find("imported").isPresent());
        assertEquals("console.log('hi')",
                Files.readString(temp.resolve("fw/mcp/imported/run.js"), StandardCharsets.UTF_8));
    }

    @Test
    void testImportFromMissingMetaThrows() throws Exception {
        Path pkg = temp.resolve("pkg-empty");
        Files.createDirectories(pkg);

        assertThrows(AgentException.class, () -> registry.importFrom(pkg));
    }

    @Test
    void testSaveRequiresNonBlankId() {
        McpServerDef def = new McpServerDef("  ", "x", "x", McpServerDef.TYPE_STDIO,
                "echo", List.of(), null, Map.of(), true);

        assertThrows(IllegalArgumentException.class, () -> registry.save(def));
    }

    @Test
    void testAuthIsolationPersists() {
        UserAuthIsolator auth = new UserAuthIsolator(dirs, mapper);
        auth.load();

        assertFalse(auth.isAuthorized("github"));

        auth.authorize("github");
        assertTrue(auth.isAuthorized("github"));

        // 重新加载后授权保持（持久化生效）
        UserAuthIsolator reloaded = new UserAuthIsolator(dirs, mapper);
        reloaded.load();
        assertTrue(reloaded.isAuthorized("github"));
        assertTrue(reloaded.revoke("github"));
        assertFalse(reloaded.isAuthorized("github"));
    }

    private void writeServer(String id, boolean enabled) throws Exception {
        Path file = temp.resolve("fw/mcp/" + id + "/meta.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"%s","name":"%s MCP","description":"演示服务","type":"STDIO",
                 "command":"echo","args":["-n"],"endpointUrl":"","env":{},"enabled":%s}
                """.formatted(id, id, enabled), StandardCharsets.UTF_8);
    }

    private void setField(Object target, String name, String value) throws Exception {
        Field field = WorkspaceDirs.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
