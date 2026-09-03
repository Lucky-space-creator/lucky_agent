package com.lucky.agent.common.dto;

/**
 * 工具执行结果（错误即数据）。
 *
 * <p>工具执行不向上抛异常，统一返回本对象，由模型读取错误后重试或换路径。
 * {@code suspended} 表示高危操作被裁决为需用户确认（ASK），引擎据此发出 ask 事件。</p>
 *
 * @param ok        是否成功
 * @param data      成功时的数据
 * @param error     失败时的错误信息
 * @param suspended 是否挂起待用户确认
 */
public record ToolResult(boolean ok, Object data, String error, boolean suspended) {

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null, false);
    }

    public static ToolResult error(String error) {
        return new ToolResult(false, null, error, false);
    }

    /** 需用户确认而挂起（不视为失败，模型侧等待 ASK 回调）。 */
    public static ToolResult suspended(String error) {
        return new ToolResult(false, null, error, true);
    }

    public boolean isError() {
        return !ok;
    }
}
