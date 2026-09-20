package com.lucky.agent.core.runtime.contract;

import java.util.Map;

/**
 * 执行产物（文件、命令输出、结构化结果等），统一随 {@link ExecutionResult} 返回。
 *
 * @param kind        产物类型（file / command / json / text …）
 * @param ref         引用（路径/URI/标识）
 * @param description 说明
 * @param meta        附加元数据
 */
public record Artifact(String kind, String ref, String description, Map<String, Object> meta) {

    public Artifact {
        meta = (meta == null) ? Map.of() : Map.copyOf(meta);
    }

    public static Artifact file(String path) {
        return new Artifact("file", path, null, Map.of());
    }

    public static Artifact of(String kind, String ref, String description) {
        return new Artifact(kind, ref, description, Map.of());
    }
}
