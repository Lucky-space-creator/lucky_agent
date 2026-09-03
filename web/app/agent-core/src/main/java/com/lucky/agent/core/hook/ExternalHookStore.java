package com.lucky.agent.core.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部 Hook 配置存储：JSON 落盘于 {@code <frameworkRoot>/.config/external-hooks.json}。
 */
@Slf4j
@Component
public class ExternalHookStore {

    private static final String FILE_NAME = "external-hooks.json";

    private final Path storeFile;
    private final ObjectMapper objectMapper;

    /** 构造外部 Hook 配置存储；多构造函数场景需显式标注 @Autowired 供 Spring 装配。 */
    @Autowired
    public ExternalHookStore(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this(dirs.configDir().resolve(FILE_NAME), objectMapper);
    }

    /** 包级构造，便于单元测试注入临时落盘路径。 */
    ExternalHookStore(Path storeFile, ObjectMapper objectMapper) {
        this.storeFile = storeFile;
        this.objectMapper = objectMapper;
    }

    /** 读取全部外部 Hook 配置（文件缺失返回空列表）。 */
    public List<ExternalHookConfig> load() {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            ExternalHookConfig[] configs = objectMapper.readValue(json, ExternalHookConfig[].class);
            return new ArrayList<>(List.of(configs));
        } catch (Exception e) {
            log.error("加载外部 Hook 配置失败：{}", storeFile, e);
            return new ArrayList<>();
        }
    }

    /** 保存全部外部 Hook 配置。 */
    public synchronized void save(List<ExternalHookConfig> configs) {
        try {
            Files.createDirectories(storeFile.getParent());
            Files.write(storeFile, objectMapper.writeValueAsBytes(configs));
            log.info("外部 Hook 配置已保存，共 {} 个", configs.size());
        } catch (Exception e) {
            log.error("保存外部 Hook 配置失败：{}", storeFile, e);
        }
    }

    public Path storeFile() {
        return storeFile;
    }
}
