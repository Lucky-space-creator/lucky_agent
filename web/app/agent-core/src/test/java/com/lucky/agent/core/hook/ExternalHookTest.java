package com.lucky.agent.core.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.PermissionDecision;
import com.lucky.agent.common.dto.HookEvent;
import com.lucky.agent.common.dto.HookEventName;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部 Hook 单元测试（事件名过滤 / Webhook 裁决解析 / 管理器增删生效）。
 */
class ExternalHookTest {

    @TempDir
    Path tempDir;

    @Test
    void testConfig_EventNameMatching() {
        ExternalHookConfig all = new ExternalHookConfig("1", "all", "WEBHOOK", "", 0, true,
                null, "http://x", "POST", null, 10, null, null);
        assertTrue(all.matches("PreToolUse"), "空事件名应监听全部");

        ExternalHookConfig filter = new ExternalHookConfig("2", "filter", "WEBHOOK", "PreToolUse", 1, true,
                null, "http://x", "POST", null, 10, null, null);
        assertTrue(filter.matches("PreToolUse"));
        assertFalse(filter.matches("Stop"), "事件名不一致不应触发");
    }

    @Test
    void testWebhookHook_AppliesDenyDecision() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"decision\":\"DENY\",\"decisionReason\":\"外部策略拒绝\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ExternalHookConfig config = new ExternalHookConfig("h", "webhook", "WEBHOOK", "PreToolUse", 0, true,
                    null, "http://localhost:" + port + "/hook", "POST", null, 5, null, null);
            WebhookHook hook = new WebhookHook(config);

            HookEvent event = new HookEvent(HookEventName.PRE_TOOL_USE, "sess-1").toolName("file_write");
            HookEvent result = hook.onEvent(event);

            assertEquals(PermissionDecision.DENY, result.decision());
            assertEquals("外部策略拒绝", result.decisionReason());
            assertTrue(result.isDenied(), "deny-wins 应阻断");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testWebhookHook_DisabledPassesThrough() throws IOException {
        ExternalHookConfig config = new ExternalHookConfig("d", "disabled", "WEBHOOK", "PreToolUse", 0, false,
                null, "http://localhost:1/hook", "POST", null, 5, null, null);
        WebhookHook hook = new WebhookHook(config);

        HookEvent event = new HookEvent(HookEventName.PRE_TOOL_USE, "sess-2").toolName("file_write");
        HookEvent result = hook.onEvent(event);

        assertEquals(null, result.decision(), "停用的 Hook 不应触发调用");
    }

    @Test
    void testManager_SaveDeleteReloadImmediate() {
        ExternalHookStore store = new ExternalHookStore(tempDir.resolve("hooks.json"), new ObjectMapper());
        ExternalHookManager manager = new ExternalHookManager(store, new ExternalHookFactory());

        assertTrue(manager.list().isEmpty());

        ExternalHookConfig saved = manager.save(new ExternalHookConfig(null, "s", "WEBHOOK", "", 0, true,
                null, "http://x", "POST", null, 5, null, null));
        assertTrue(saved.id() != null && !saved.id().isBlank(), "保存时应自动生成 id");
        assertEquals(1, manager.list().size(), "配置应即时落盘");
        assertEquals(1, manager.hooks().size(), "适配器应即时生效");

        assertTrue(manager.delete(saved.id()));
        assertTrue(manager.hooks().isEmpty());
        assertTrue(manager.list().isEmpty());
    }
}
