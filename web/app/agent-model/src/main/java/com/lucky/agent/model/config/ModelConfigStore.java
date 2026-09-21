package com.lucky.agent.model.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.model.api.dto.AgentSettings;
import com.lucky.agent.model.api.dto.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * 全局设置存储：JSON 落盘于框架目录（{@code <frameworkRoot>/settings.json}），
 * 承载模型端点列表与全局推理深度。唯一配置来源为 {@code settings.json}，不再兼容旧版
 * {@code model.json}。
 * <p><b>D8 / P1-4 fix</b>：API Key 不以明文写盘——保存时用 AES-GCM（随机 32 字节
 * 主密钥，存于 {@code <frameworkRoot>/.settings-key}）加密 {@code apiKey} 字段，
 * 读取时解密回明文供模型调用。历史明文值透传并在下次保存时自动迁移为密文。</p>
 */
@Slf4j
@Component
public class ModelConfigStore {

    private static final String FILE_NAME = "settings.json";
    private static final String KEY_FILE_NAME = ".settings-key";

    private final Path storeFile;
    private final ObjectMapper objectMapper;
    private final SettingsKeyCipher keyCipher;

    /** 构造全局设置存储；多构造函数场景需显式标注 @Autowired 供 Spring 装配。 */
    @Autowired
    public ModelConfigStore(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this(dirs.frameworkRoot().resolve(FILE_NAME), objectMapper);
    }

    /** 包级构造，便于单元测试注入临时落盘路径。 */
    ModelConfigStore(Path storeFile, ObjectMapper objectMapper) {
        this.storeFile = storeFile;
        this.objectMapper = objectMapper;
        this.keyCipher = new SettingsKeyCipher(storeFile.resolveSibling(KEY_FILE_NAME));
    }

    /** 读取全部设置（apiKey 解密回明文）；配置文件缺失时返回空设置。 */
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
            if (settings.models() != null) {
                settings.models().forEach(c -> c.apiKey(keyCipher.decryptIfNeeded(c.apiKey())));
            }
            return settings;
        } catch (Exception e) {
            log.error("加载全局设置失败：{}", storeFile, e);
            return AgentSettings.empty();
        }
    }

    /** 保存全部设置：格式化 JSON 落盘，API Key 加密写入（D8，禁止明文）。 */
    public synchronized void save(AgentSettings settings) {
        try {
            Files.createDirectories(storeFile.getParent());
            AgentSettings copy = AgentSettings.of(settings.inferenceDepth(), new ArrayList<>());
            // 预设随设置一起落盘：调用方未传入 preset 时，沿用已落盘的旧值，避免「保存端点」把预设抹掉
            if (settings.agentPreset() != null) {
                copy.agentPreset(settings.agentPreset());
            } else {
                copy.agentPreset(load().agentPreset());
            }
            if (settings.models() != null) {
                for (ModelConfig c : settings.models()) {
                    ModelConfig cc = c.copy();
                    cc.apiKey(keyCipher.encryptIfNeeded(c.apiKey()));
                    copy.models().add(cc);
                }
            }
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(copy);
            Files.write(storeFile, bytes);
            int count = settings.models() == null ? 0 : settings.models().size();
            log.info("全局设置已保存，共 {} 个端点，推理深度 {}", count, settings.inferenceDepth());
        } catch (Exception e) {
            log.error("保存全局设置失败：{}", storeFile, e);
        }
    }

    /**
     * 单独保存 Agent 预设（其余设置保持磁盘现状）。
     *
     * <p>与 {@link #save(AgentSettings)} 分道，避免「改预设」与「改端点」两条写路径互相覆盖。</p>
     */
    public synchronized void savePreset(com.lucky.agent.model.api.dto.AgentPreset preset) {
        AgentSettings current = load();
        current.agentPreset(preset);
        save(current);
    }

    public Path storeFile() {
        return storeFile;
    }
}
