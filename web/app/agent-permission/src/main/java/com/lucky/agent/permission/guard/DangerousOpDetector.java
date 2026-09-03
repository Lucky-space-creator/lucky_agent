package com.lucky.agent.permission.guard;

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
     * 评估操作风险等级。
     *
     * @param op 文件操作
     * @return 删除/执行/覆盖返回 HIGH，其余 LOW
     */
    public Risk assess(FileOp op) {
        if (op == null || op.opType() == null) {
            return Risk.LOW;
        }
        return switch (op.opType()) {
            case DELETE, EXEC -> Risk.HIGH;
            case WRITE, RENAME, READ, LIST, MKDIR, STAT -> Risk.LOW;
        };
    }

    /** 是否高风险（删除/执行/覆盖）。 */
    public boolean isDangerous(FileOp op) {
        return assess(op) == Risk.HIGH;
    }
}
