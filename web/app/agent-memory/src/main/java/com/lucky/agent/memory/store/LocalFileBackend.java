package com.lucky.agent.memory.store;

import com.lucky.agent.common.util.JsonlUtil;

import java.nio.file.Path;
import java.util.List;

/**
 * 本机文件后端：JSONL 追加写 + 读取（供用户轨/平台轨复用）。
 */
public class LocalFileBackend {

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
}
