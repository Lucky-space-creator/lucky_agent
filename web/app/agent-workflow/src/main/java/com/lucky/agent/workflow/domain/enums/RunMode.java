package com.lucky.agent.workflow.domain.enums;

/**
 * 工作流执行模式。
 * <ul>
 *   <li>SYNC：调用线程内同步执行，返回最终结果。</li>
 *   <li>ASYNC：提交到调度线程池异步执行，立即返回实例 ID。</li>
 * </ul>
 */
public enum RunMode {
    SYNC,
    ASYNC
}
