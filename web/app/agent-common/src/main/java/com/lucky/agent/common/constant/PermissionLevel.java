package com.lucky.agent.common.constant;

/**
 * 权限级别枚举（只读 / 修改 / 全部）。
 *
 * <p>用户添加工作空间时三选一，随工作区配置存 {@code <frameworkRoot>/.config/}，
 * 每次对话调用带入上下文作为体验层引导；精确权限由 {@code deny > ask > allow} 规则链裁决，
 * 执行臂为物理执行边界与最终裁决者。</p>
 */
public enum PermissionLevel {

    /** 只读：仅可读取工作区内文件，禁止任何写操作。 */
    READ_ONLY("read", "只读"),

    /** 修改文件：可读写工作区内文件，禁止执行命令。 */
    MODIFY("modify", "修改文件"),

    /** 全部权限：可读写文件并执行命令，仍受 deny 规则与执行臂硬边界约束。 */
    FULL("full", "全部权限");

    private final String code;
    private final String label;

    PermissionLevel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 默认权限级别：新建工作区默认「修改文件」。 */
    public static PermissionLevel defaultValue() {
        return MODIFY;
    }

    /** 按 code 解析，未知值回落默认级别。 */
    public static PermissionLevel fromCode(String code) {
        if (code == null) {
            return defaultValue();
        }
        for (PermissionLevel level : values()) {
            if (level.code.equalsIgnoreCase(code)) {
                return level;
            }
        }
        return defaultValue();
    }
}
