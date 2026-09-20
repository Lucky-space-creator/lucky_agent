package com.lucky.agent.workflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变量作用域：工作流/节点私有的键值容器。
 * <p>工作流级作用域在节点间共享；节点级作用域仅在一次节点执行内有效。
 * 通过 {@link #snapshot()} 可创建隔离副本，避免并行分支互相污染。</p>
 */
public class VariableScope {

    private final Map<String, Object> data = new ConcurrentHashMap<>();

    public VariableScope() {
    }

    public VariableScope(Map<String, Object> seed) {
        if (seed != null) {
            data.putAll(seed);
        }
    }

    public void set(String key, Object value) {
        data.put(key, value);
    }

    public Object get(String key) {
        return data.get(key);
    }

    public boolean contains(String key) {
        return data.containsKey(key);
    }

    public Map<String, Object> asMap() {
        return data;
    }

    /** Jackson 序列化入口：变量以 data 字段输出。 */
    @JsonProperty("data")
    public Map<String, Object> getData() {
        return data;
    }

    /** 合并另一个作用域（同键覆盖）。 */
    public void merge(VariableScope other) {
        if (other != null) {
            data.putAll(other.data);
        }
    }

    /** 创建隔离副本，供并行分支独立写入。 */
    public VariableScope snapshot() {
        return new VariableScope(new LinkedHashMap<>(data));
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
