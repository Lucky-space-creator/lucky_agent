package com.lucky.agent.workspace.service.impl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.lucky.agent.common.constant.PermissionLevel;
import com.lucky.agent.common.constant.WorkspaceDirs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作空间配置的持久化安全（台账 #23）。
 *
 * <p>修复前两个问题叠加成「一次崩溃丢掉全部工作空间」：</p>
 * <ol>
 *   <li>{@code save()} 用 {@code TRUNCATE_EXISTING} 直接写目标文件 —— 先截断后写，
 *       中途崩溃留下半截 JSON；</li>
 *   <li>{@code load()} 解析失败只 {@code log.error} 就继续用<b>空列表</b>跑，
 *       之后任一次建/删工作空间都会把原文件覆盖成 {@code []}。</li>
 * </ol>
 * <p>现在改为「临时文件 + 原子替换」，并在解析失败后首次保存前把原文件备份成
 * {@code workspaces.json.corrupt-<时间戳>}，让不可恢复的数据丢失变成可恢复的告警。</p>
 */
class WorkspaceConfigCenterPersistenceTest {

    @Test
    @DisplayName("#23 配置文件损坏时，首次保存必须先备份原文件，不能直接覆盖")
    void corruptConfigIsBackedUpBeforeOverwrite() throws Exception {
        Path framework = Files.createTempDirectory("lucky-wscfg-corrupt-");
        WorkspaceDirs dirs = dirsAt(framework);
        Path store = dirs.configDir().resolve("workspaces.json");
        Files.createDirectories(store.getParent());
        // 模拟「写到一半崩溃」留下的半截 JSON
        Files.writeString(store, "[{\"workspaceId\":\"w-old\",\"name\":\"旧空间\",", StandardCharsets.UTF_8);

        WorkspaceConfigCenter center = new WorkspaceConfigCenter(dirs, json());
        assertThat(center.listWorkspaces())
                .as("解析失败要能启动（不能因为一个坏文件起不来），但不该带出半截数据")
                .isEmpty();

        Path project = Files.createDirectories(Files.createTempDirectory("lucky-wscfg-proj-"));
        center.createWorkspace("项目一", project, PermissionLevel.READ_ONLY);

        try (var files = Files.list(store.getParent())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names)
                    .as("原文件必须先被备份，否则用户那份可能只是格式错、仍可人工修复的数据就没了")
                    .anyMatch(n -> n.startsWith("workspaces.json.corrupt-"));
            assertThat(names)
                    .as("临时文件必须被清理，不能残留在配置目录")
                    .noneMatch(n -> n.endsWith(".tmp"));
        }

        WorkspaceConfigCenter reloaded = new WorkspaceConfigCenter(dirs, json());
        assertThat(reloaded.listWorkspaces()).hasSize(1);
    }

    @Test
    @DisplayName("#23 配置正常时不得产生无谓的备份文件")
    void healthyConfigIsNotBackedUp() throws Exception {
        Path framework = Files.createTempDirectory("lucky-wscfg-ok-");
        WorkspaceDirs dirs = dirsAt(framework);
        Path store = dirs.configDir().resolve("workspaces.json");
        Files.createDirectories(store.getParent());

        Path project = Files.createDirectories(Files.createTempDirectory("lucky-wscfg-proj-"));
        WorkspaceConfigCenter center = new WorkspaceConfigCenter(dirs, json());
        center.createWorkspace("项目一", project, PermissionLevel.READ_ONLY);

        try (var files = Files.list(store.getParent())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names)
                    .as("每次保存都备份会把配置目录撑成一堆垃圾")
                    .noneMatch(n -> n.contains(".corrupt-"));
            assertThat(names).contains("workspaces.json");
        }

        WorkspaceConfigCenter reloaded = new WorkspaceConfigCenter(dirs, json());
        assertThat(reloaded.listWorkspaces()).hasSize(1);
        assertThat(reloaded.listWorkspaces().get(0).name()).isEqualTo("项目一");
    }

    /**
     * 生产用的 ObjectMapper 由 Spring 注入，自带 {@code JavaTimeModule}；
     * 能力模块的测试 classpath 上没有 jsr310，故这里给 {@link Instant} 补一对最简
     * 序列化器，避免 {@code Workspace.createdAt} 触发 {@code UnsupportedTypeSerializer}。
     */
    private static ObjectMapper json() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getText());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    private static WorkspaceDirs dirsAt(Path root) throws Exception {
        WorkspaceDirs dirs = new WorkspaceDirs();
        set(dirs, "frameworkRoot", root.toString());
        set(dirs, "workspaceRoot", root.resolve("ws").toString());
        dirs.init();
        return dirs;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
