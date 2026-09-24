package com.lucky.agent.cli;

/**
 * CLI 退出码契约（见 CLI 方案 §4.11）。
 *
 * <p>刻意只保留 6 个码、不做冗余细分：脚本与 CI 只需要区分「成功 / 一切失败 / 用法错 /
 * 权限被拒 / 预算耗尽 / 挂起未决」六种可编程反应。不采纳「输入错误」与「参数错误」分开
 * 编号的做法——在本项目语义下两者重合，多一个码只会增加调用方的适配负担。</p>
 */
public final class CliExitCode {

    /** 成功：本轮正常结束，且无未决授权。 */
    public static final int SUCCESS = 0;

    /** 通用失败：内核报错、模型调用失败、未捕获异常。 */
    public static final int FAILURE = 1;

    /** 用法错误：参数非法、工作空间不存在、无法解析的选项、多候选工作空间未指定。 */
    public static final int USAGE = 2;

    /** 权限拒绝：ASK 被用户拒绝，或 headless 下以「默认拒绝」处理。 */
    public static final int PERMISSION_DENIED = 3;

    /** 预算耗尽：回合上限或 token 预算触顶。 */
    public static final int BUDGET_EXHAUSTED = 4;

    /** 挂起未决：会话结束时仍存在未决的 ASK/OPTIONS（异常路径）。 */
    public static final int PENDING_UNRESOLVED = 5;

    private CliExitCode() {
    }

    /** 退出码的可读说明（供 {@code /help} 与诊断输出）。 */
    public static String describe(int code) {
        return switch (code) {
            case SUCCESS -> "成功";
            case FAILURE -> "失败";
            case USAGE -> "用法错误";
            case PERMISSION_DENIED -> "权限拒绝";
            case BUDGET_EXHAUSTED -> "预算耗尽";
            case PENDING_UNRESOLVED -> "挂起未决";
            default -> "未知退出码";
        };
    }
}
