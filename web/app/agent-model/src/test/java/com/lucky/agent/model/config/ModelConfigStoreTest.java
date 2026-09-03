package com.lucky.agent.model.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.model.api.dto.AgentSettings;
import com.lucky.agent.model.api.dto.InferenceDepth;
import com.lucky.agent.model.api.dto.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelConfigStore 全局设置读写 单元测试（唯一配置来源 settings.json，不再兼容旧 model.json）。
 */
class ModelConfigStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSaveLoad_RoundTripKeepsDepthAndModels() throws Exception {
        Path file = tempDir.resolve("settings.json");
        ModelConfigStore store = new ModelConfigStore(file, objectMapper);
        store.save(AgentSettings.of(InferenceDepth.DEEP, List.of(ModelConfig.of("阿里云", "https://example.com/v1", "qwen"))));

        AgentSettings result = new ModelConfigStore(file, objectMapper).load();
        assertEquals(InferenceDepth.DEEP, result.inferenceDepth(), "推理深度应完整往返");
        assertEquals(1, result.models().size(), "模型端点应完整往返");
        assertEquals("阿里云", result.models().get(0).name());
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"inferenceDepth\""), "settings.json 应包含推理深度字段");
        assertTrue(json.contains("\"DEEP\""), "推理深度应写为枚举名而非数字");
    }

    @Test
    void testLoad_MissingFileReturnsDefaults() {
        AgentSettings result = new ModelConfigStore(tempDir.resolve("settings.json"), objectMapper).load();
        assertEquals(InferenceDepth.defaultValue(), result.inferenceDepth());
        assertTrue(result.models().isEmpty());
    }

    @Test
    void testLoad_LegacyNumericDepthParsedToEnum() throws Exception {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, """
                {"inferenceDepth": 4, "models": []}
                """, StandardCharsets.UTF_8);

        AgentSettings result = new ModelConfigStore(file, objectMapper).load();
        assertEquals(InferenceDepth.DEEP, result.inferenceDepth(), "旧版数字 4 应解析为 DEEP");
    }

    @Test
    void testLoad_IgnoresLegacyModelJson() throws Exception {
        // 只读 settings.json：即使同目录存在旧 model.json 也不读取、不迁移、不删除
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, """
                {"inferenceDepth": "BALANCED", "models": []}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("model.json"),
                objectMapper.writeValueAsString(new ModelConfig[]{
                        ModelConfig.of("旧模型", "https://example.com/v1", "qwen")}),
                StandardCharsets.UTF_8);

        AgentSettings result = new ModelConfigStore(settings, objectMapper).load();
        assertEquals(InferenceDepth.BALANCED, result.inferenceDepth());
        assertTrue(result.models().isEmpty(), "旧 model.json 内容不应被读取");
        assertTrue(Files.exists(tempDir.resolve("model.json")), "旧 model.json 不应被删除");
    }
}
