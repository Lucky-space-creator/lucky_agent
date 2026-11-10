package com.lucky.agent.permission.support.guard;

import com.lucky.agent.common.dto.FileOp;

/**
 * 危险操作识别（§4.12）：识别删除/执行/覆盖等高风险动作 → 转 core 的 ASK 确认。
 *
 * <p>仅作识别与风险标注，不在本模块硬拦截；确认由 core 的 ASK 流程与用户完成，
 * 执行臂硬边界兜底。</p>
 */
public class DangerousOpDetector {

    /** 风险等级。 */
    public enum Risk {
        LOW,
        HIGH
    }

    /**
     * 评估操作风险等级（含「是否覆盖已有文件」维度）。
     *
     * <p>台账 #7：类注释一直写「删除/执行/覆盖返回 HIGH」，实现却只标了 DELETE/EXEC，
     * 于是「修改文件」档下覆盖一份 2000 行源文件直接 ALLOW，既不确认也不受一次性放行约束。
     * 覆盖与删除同属不可逆动作，必须走同一个确认通道。</p>
     *
     * @param op           文件操作
     * @param targetExists 目标是否已存在（WRITE 才需要：新建文件不是覆盖）
     * @return 删除/执行/重命名/覆盖返回 HIGH，其余 LOW
     */
    public Risk assess(FileOp op, boolean targetExists) {
        if (op == null || op.opType() == null) {
            return Risk.LOW;
        }
        return switch (op.opType()) {
            // RENAME 会以 REPLACE_EXISTING 移动，可能覆盖目标文件 → 与删除同风险
            case DELETE, EXEC, RENAME -> Risk.HIGH;
            // 覆盖已有内容才叫「覆盖」；新建空文件是低风险
            case WRITE -> targetExists ? Risk.HIGH : Risk.LOW;
            case READ, LIST, MKDIR, STAT -> Risk.LOW;
        };
    }

    /**
     * 评估操作风险等级（无法判断目标是否存在时的保守重载）。
     *
     * <p>按「可能覆盖」处理 WRITE —— 安全枚举的默认值必须偏向严格（fail-closed），
     * 不能因为调用方拿不到存在性就把覆盖当新建放行。</p>
     */
    public Risk assess(FileOp op) {
        return assess(op, true);
    }

    /** 是否高风险（删除/执行/覆盖）。 */
    public boolean isDangerous(FileOp op) {
        return assess(op) == Risk.HIGH;
    }

    /** 是否高风险，显式传入目标是否存在。 */
    public boolean isDangerous(FileOp op, boolean targetExists) {
        return assess(op, targetExists) == Risk.HIGH;
    }
}
