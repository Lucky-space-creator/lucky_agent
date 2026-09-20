package com.lucky.agent.memory.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.util.JsonlUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机文件后端：JSONL 追加写 + 读取 + 整文件重写（供用户轨/平台轨复用）。
 */
@Slf4j
public class LocalFileBackend {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 追加一条记录到 JSONL 文件（父目录自动创建）。
     *
     * @param file  目标文件
     * @param value 记录对象
     */
    public void append(Path file, Object value) {
        JsonlUtil.append(file, value);
    }

    /**
     * 读取 JSONL 全部记录。
     *
     * @param file 文件
     * @param type 记录类型
     * @param <T>  类型
     * @return 记录列表（文件不存在返回空列表）
     */
    public <T> List<T> read(Path file, Class<T> type) {
        return JsonlUtil.readAll(file, line -> JsonlUtil.parseLine(line, type));
    }

    /**
     * 整文件重写（做梦清理/衰减后落盘）；空列表删除文件。
     *
     * @param file   目标文件
     * @param values 新记录列表
     */
    public void rewrite(Path file, List<?> values) {
        List<String> lines = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                try {
                    lines.add(objectMapper.writeValueAsString(value));
                } catch (Exception e) {
                    log.warn("JSONL 序列化失败，跳过该行：{}", e.getMessage());
                }
            }
        }
        JsonlUtil.rewrite(file, lines);
    }
}
