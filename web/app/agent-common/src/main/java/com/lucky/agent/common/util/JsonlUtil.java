package com.lucky.agent.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.exception.AgentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * JSONL（JSON Lines）读写工具，用于记忆落盘（mem.jsonl）等场景。
 */
public final class JsonlUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonlUtil() {
    }

    /**
     * 追加一行 JSON 对象到 JSONL 文件（原子写：写临时文件再 rename）。
     *
     * @param file  目标文件
     * @param value 待序列化对象
     */
    public static void append(Path file, Object value) {
        try {
            byte[] line = (MAPPER.writeValueAsString(value) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                Files.write(file, line, StandardOpenOption.APPEND);
            } else {
                Files.write(file, line);
            }
        } catch (JsonProcessingException e) {
            throw new AgentException("JSONL_SERIALIZE", "JSONL 序列化失败", e);
        } catch (IOException e) {
            throw new AgentException("JSONL_IO", "JSONL 写入失败：" + file, e);
        }
    }

    /**
     * 读取 JSONL 全部行并反序列化。
     *
     * @param file      文件
     * @param converter 行反序列化器
     * @param <T>       类型
     * @return 解析结果列表（文件不存在返回空列表）
     */
    public static <T> List<T> readAll(Path file, Function<String, T> converter) {
        List<T> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                result.add(converter.apply(line));
            }
            return result;
        } catch (IOException e) {
            throw new AgentException("JSONL_IO", "JSONL 读取失败：" + file, e);
        }
    }

    /** 读取全部原始行。 */
    public static List<String> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException("JSONL_IO", "JSONL 读取失败：" + file, e);
        }
    }

    /** 解析单行 JSON 到指定类型。 */
    public static <T> T parseLine(String line, Class<T> type) {
        try {
            return MAPPER.readValue(line, type);
        } catch (JsonProcessingException e) {
            throw new AgentException("JSONL_PARSE", "JSONL 行解析失败", e);
        }
    }
}
