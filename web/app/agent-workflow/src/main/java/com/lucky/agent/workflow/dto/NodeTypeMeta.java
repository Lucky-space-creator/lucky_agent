package com.lucky.agent.workflow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 节点类型元数据：供前端画布动态渲染「组件面板」与「属性表单」。
 *
 * <p><b>为何由后端下发：</b>节点类型与各自的 {@code config} 键由引擎执行器决定
 * （见 {@code engine/executors/*}）。若前端硬编码一份 schema，则每次后端新增节点类型或
 * 改动 config 键，前端都会静默失配 —— 表现为「表单填了但引擎读不到」这类难以定位的问题。
 * 由后端作为唯一事实源下发，两端不再各维护一份。</p>
 *
 * @param type        节点类型枚举名
 * @param label       展示名
 * @param description 用途说明
 * @param category    分组（structure / model / action / logic / composite）
 * @param singleton   是否全流程唯一（START 为 true，画布侧据此禁止重复添加）
 * @param required    是否为流程必需（START / END 为 true）
 * @param fields      该类型的 config 字段定义（按顺序渲染）
 */
public record NodeTypeMeta(
        @JsonProperty("type") String type,
        @JsonProperty("label") String label,
        @JsonProperty("description") String description,
        @JsonProperty("category") String category,
        @JsonProperty("singleton") boolean singleton,
        @JsonProperty("required") boolean required,
        @JsonProperty("fields") List<ConfigField> fields) {

    /**
     * config 字段定义。
     *
     * @param key         写入 {@code NodeDef.config} 的键名（必须与执行器读取的键一致）
     * @param label       展示名
     * @param input      控件类型：TEXT / TEXTAREA / NUMBER / BOOLEAN / SELECT / JSON
     *                   （JSON = 文本域输入，前端解析为对象后写入 config）
     * @param required    是否必填
     * @param placeholder 占位提示
     * @param hint        字段下方的补充说明
     * @param options     SELECT 的可选值
     * @param defaultValue 默认值（新建节点时预填）
     */
    public record ConfigField(
            @JsonProperty("key") String key,
            @JsonProperty("label") String label,
            @JsonProperty("input") String input,
            @JsonProperty("required") boolean required,
            @JsonProperty("placeholder") String placeholder,
            @JsonProperty("hint") String hint,
            @JsonProperty("options") List<Option> options,
            @JsonProperty("defaultValue") Object defaultValue) {

        /** SELECT 选项。 */
        public record Option(@JsonProperty("value") String value, @JsonProperty("label") String label) {
        }

        public static ConfigField text(String key, String label, boolean required, String placeholder, String hint) {
            return new ConfigField(key, label, "TEXT", required, placeholder, hint, null, null);
        }

        public static ConfigField textarea(String key, String label, boolean required, String placeholder, String hint) {
            return new ConfigField(key, label, "TEXTAREA", required, placeholder, hint, null, null);
        }

        public static ConfigField number(String key, String label, String placeholder, String hint, Object defaultValue) {
            return new ConfigField(key, label, "NUMBER", false, placeholder, hint, null, defaultValue);
        }

        public static ConfigField bool(String key, String label, String hint, boolean defaultValue) {
            return new ConfigField(key, label, "BOOLEAN", false, null, hint, null, defaultValue);
        }

        public static ConfigField json(String key, String label, boolean required, String placeholder, String hint) {
            return new ConfigField(key, label, "JSON", required, placeholder, hint, null, null);
        }
    }
}
