package com.lucky.agent.workflow.adapter;

import java.util.Map;

/**
 * 工具调用适配器（集成缝隙）。
 * <p>与 {@code ToolGateway} 对接：工作流工具节点的实际执行委托给本接口。
 * 默认 {@link NoopToolAdapter} 回显参数，保证模块自洽可运行。</p>
 */
public interface ToolAdapter {

    /**
     * 调用指定工具。
     *
     * @param toolName 工具名
     * @param args     参数
     * @return 工具输出（键值结构）
     */
    Map<String, Object> call(String toolName, Map<String, Object> args);
}
