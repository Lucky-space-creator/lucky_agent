package com.lucky.agent.common.api;

/**
 * 工具注解：决定调度方式与风险提示。
 *
 * <p>只读工具可并行、写工具串行、破坏性工具转 ASK；危险操作识别（DangerousOpDetector）
 * 依据 destructive 标记将删除/覆盖/执行等高危动作转入 ASK 确认。</p>
 *
 * @param readOnly    只读：不修改任何状态，可并行执行
 * @param destructive 破坏性：删除/覆盖/执行等，需 ASK 确认
 * @param idempotent  幂等：重复执行结果一致，可安全重试
 */
public record ToolAnnotations(boolean readOnly, boolean destructive, boolean idempotent) {

    /** 只读工具（可并行）。 */
    public static ToolAnnotations readOnlyTool() {
        return new ToolAnnotations(true, false, false);
    }

    /** 写工具（串行，幂等）。 */
    public static ToolAnnotations writableTool() {
        return new ToolAnnotations(false, false, true);
    }

    /** 破坏性工具（串行，需 ASK）。 */
    public static ToolAnnotations destructiveTool() {
        return new ToolAnnotations(false, true, false);
    }
}
