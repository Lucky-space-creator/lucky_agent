package com.lucky.agent.model.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.model.api.dto.AgentSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * 全局设置存储：明文 JSON 落盘于框架目录（{@code <frameworkRoot>/settings.json}），
 * 承载模型端点列表与全局推理深度。唯一配置来源为 {@code settings.json}，不再兼容旧版
 * {@code model.json}。
 * <p>API Key 属于用户本机配置，明文存储（零托管、不加密），后端不读取密钥内容。</p>
 */
@Slf4j
@Component
public class ModelConfigStore {

    private static final String FILE_NAME = "settings.json";

    private final Path storeFile;
    private final ObjectMapper objectMapper;

    /** 构造全局设置存储；多构造函数场景需显式标注 @Autowired 供 Spring 装配。 */
    @Autowired
    public ModelConfigStore(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this(dirs.frameworkRoot().resolve(FILE_NAME), objectMapper);
    }

    /** 包级构造，便于单元测试注入临时落盘路径。 */
    ModelConfigStore(Path storeFile, ObjectMapper objectMapper) {
        this.storeFile = storeFile;
        this.objectMapper = objectMapper;
    }

    /** 读取全部设置（明文直读）；配置文件缺失时返回空设置。 */
    public AgentSettings load() {
        if (!Files.exists(storeFile)) {
            return AgentSettings.empty();
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            AgentSettings settings = objectMapper.readValue(json, AgentSettings.class);
            if (settings.models() == null) {
                settings.models(new ArrayList<>());
            }
            return settings;
        } catch (Exception e) {
            log.error("加载全局设置失败：{}", storeFile, e);
            return AgentSettings.empty();
        }
    }

    /** 保存全部设置：格式化 JSON 落盘，便于用户直接阅读与手工编辑。 */
    public synchronized void save(AgentSettings settings) {
        try {
            Files.createDirectories(storeFile.getParent());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            Files.write(storeFile, bytes);
            int count = settings.models() == null ? 0 : settings.models().size();
            log.info("全局设置已保存，共 {} 个端点，推理深度 {}", count, settings.inferenceDepth());
        } catch (Exception e) {
            log.error("保存全局设置失败：{}", storeFile, e);
        }
    }

    public Path storeFile() {
        return storeFile;
    }
}
