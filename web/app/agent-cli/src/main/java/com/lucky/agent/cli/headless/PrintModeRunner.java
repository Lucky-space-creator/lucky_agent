package com.lucky.agent.cli.headless;

import com.lucky.agent.cli.CliExitCode;
import com.lucky.agent.cli.CliOptions;
import com.lucky.agent.cli.CliTurnLoop;
import com.lucky.agent.cli.TurnOutcome;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.session.SessionHolder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * headless 一次性执行：给出提示词 → 跑完 → 用退出码表态。
 *
 * <p><b>为什么退出码必须区分这么细</b>（CLI 方案 v1 §4.11）：脚本与 CI 只能通过退出码理解结果。
 * 「一切非 0 即失败」的粗粒度会让调用方无法区分「模型报错（应重试）」与
 * 「权限被拒（应改配置）」与「预算耗尽（应调参）」—— 而这三者的处置方式完全不同。</p>
 *
 * <p><b>ASK 在 headless 下默认拒绝</b>：无交互环境里如果挂起等待，脚本会静默卡住直到超时，
 * 这是最糟的失败形态。故默认拒绝并返回退出码 3；需要脚本化放行必须显式
 * {@code --auto-approve}，且每次放行都会在 stderr 打印审计行。</p>
 */
@Slf4j
public final class PrintModeRunner {

    private final CliOptions options;
    private final CliTurnLoop turns;
    private final OutputFormatter formatter;
    private final OutputSink stderr;
    private final SessionHolder holder;

    public PrintModeRunner(CliOptions options, CliTurnLoop turns, OutputFormatter formatter,
                           OutputSink stderr, SessionHolder holder) {
        this.options = options;
        this.turns = turns;
        this.formatter = formatter;
        this.stderr = stderr;
        this.holder = holder;
    }

    /** 执行一次并返回进程退出码。 */
    public int run(String promptText) {
        long started = System.currentTimeMillis();
        CliTurnLoop.OptionChooser chooser = options.autoApprove ? this::autoPick : null;
        CliTurnLoop.Result result;
        try {
            result = turns.run(holder.ref(), promptText, options.runExtra(), chooser);
        } catch (Exception e) {
            log.error("headless 执行失败", e);
            stderr.println(stderr.theme().red("执行失败：" + e.getMessage()));
            formatter.printFinal(null, CliExitCode.FAILURE, System.currentTimeMillis() - started);
            return CliExitCode.FAILURE;
        }

        TurnOutcome outcome = result.outcome();
        int code = exitCode(result);
        formatter.printFinal(outcome, code, System.currentTimeMillis() - started);
        return code;
    }

    private int exitCode(CliTurnLoop.Result result) {
        if (result.outcome() == null) {
            return CliExitCode.FAILURE;
        }
        if (result.choiceAbandoned()) {
            // 内核在等用户做条件选择，本轮并未真正完成 —— 不能报成功
            return CliExitCode.PENDING_UNRESOLVED;
        }
        return result.outcome().exitCode(result.askDenied());
    }

    /**
     * 自动选择（仅 {@code --auto-approve} 时启用）：取推荐项，无推荐取第一项。
     *
     * <p>与内核「条件选择超时自动选优」保持同一口径（{@code ConversationManager#autoPickOnTimeout}），
     * 避免同一语义在超时路径与显式路径上给出不同结果。</p>
     */
    private String autoPick(String question, List<Map<String, Object>> optionList) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        for (Map<String, Object> o : optionList) {
            if (Boolean.TRUE.equals(o.get("recommended"))) {
                Object label = o.get("label");
                if (label != null) {
                    return String.valueOf(label);
                }
            }
        }
        Object first = optionList.get(0).get("label");
        return first == null ? null : String.valueOf(first);
    }
}
