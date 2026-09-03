package com.lucky.agent.persona.center;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.WorkspaceDirs;
import com.lucky.agent.persona.api.dto.Persona;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 人格存储：{@code <frameworkRoot>/.config/persona.json} 读写�? */

@Slf4j
public class PersonaStore {

    
    private static final String FILE_NAME = "persona.json";

    private final Path file;
    private final ObjectMapper objectMapper;

    public PersonaStore(WorkspaceDirs dirs, ObjectMapper objectMapper) {
        this.file = dirs.configDir().resolve(FILE_NAME);
        this.objectMapper = objectMapper;
    }

    /**
     * 持久化状态�?     *
     * @param active 当前激活人�?ID
     * @param custom 用户自定义人格列�?     */
    public record PersonaState(String active, List<Persona> custom) {
    }

    /** 读取持久化状态（文件不存在返回空状态）�?*/
    public PersonaState load() {
        if (!Files.exists(file)) {
            return new PersonaState(null, new ArrayList<>());
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, PersonaState.class);
        } catch (Exception e) {
            log.warn("读取人格配置失败：{}", file, e);
            return new PersonaState(null, new ArrayList<>());
        }
    }

    /** 保存持久化状态�?*/
    public void save(PersonaState state) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, objectMapper.writeValueAsBytes(state));
        } catch (Exception e) {
            log.error("保存人格配置失败：{}", file, e);
        }
    }
}
