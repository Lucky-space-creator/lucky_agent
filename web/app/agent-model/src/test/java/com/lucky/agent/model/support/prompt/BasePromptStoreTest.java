package com.lucky.agent.model.support.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基座提示词外置：{@code <frameworkRoot>/LUCKY.md} 读写与降级。
 */
class BasePromptStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultTemplateOnFirstUse() {
        Path file = tempDir.resolve("LUCKY.md");
        BasePromptStore store = new BasePromptStore(file);

        assertTrue(Files.exists(file), "首次使用应生成默认模板");
        String content = store.basePrompt();
        assertTrue(content.contains("lucky_agent"), "模板应包含内置基座提示词");
    }

    @Test
    void readsUserEditedContentImmediately() throws Exception {
        Path file = tempDir.resolve("LUCKY.md");
        BasePromptStore store = new BasePromptStore(file);
        Files.writeString(file, "你是我的私人助手，回答一律用中文。", StandardCharsets.UTF_8);

        assertEquals("你是我的私人助手，回答一律用中文。", store.basePrompt());

        // 不缓存：改完保存立即生效
        Files.writeString(file, "改成英文回答。", StandardCharsets.UTF_8);
        assertEquals("改成英文回答。", store.basePrompt());
    }

    @Test
    void fallsBackToBuiltinWhenFileIsBlank() throws Exception {
        Path file = tempDir.resolve("LUCKY.md");
        BasePromptStore store = new BasePromptStore(file);
        Files.writeString(file, "   \n  ", StandardCharsets.UTF_8);

        assertEquals(BasePromptStore.DEFAULT_BASE_PROMPT, store.basePrompt());
        // 不覆盖用户文件
        assertEquals("   \n  ", Files.readString(file, StandardCharsets.UTF_8));
    }
}
