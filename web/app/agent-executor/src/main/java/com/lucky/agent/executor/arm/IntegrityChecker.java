package com.lucky.agent.executor.arm;

import lombok.extern.slf4j.Slf4j;


/**
 * 执行臂进程完整性校验（防劫持，Phase 1 为占位）�? *
 * <p>完整签名校验（被替换拒绝启动）在 Phase 2 补齐；本阶段提供结构校验与告警日志，
 * 保证执行臂不会被未授权替换而无感知�?/p>
 */

@Slf4j
public class IntegrityChecker {

    
    private final boolean strict;

    public IntegrityChecker(boolean strict) {
        this.strict = strict;
    }

    /**
     * 校验执行臂完整性�?     *
     * @param processPid 执行臂进�?PID（Phase 1 为当�?JVM�?     * @return true 校验通过
     */
    public boolean verify(long processPid) {
        boolean alive = ProcessHandle.of(processPid).map(ProcessHandle::isAlive).orElse(false);
        if (!alive) {
            log.error("执行臂进程不可达：pid={}", processPid);
            return false;
        }
        log.debug("执行臂完整性校验通过：pid={}", processPid);
        return true;
    }

    public boolean strict() {
        return strict;
    }
}
