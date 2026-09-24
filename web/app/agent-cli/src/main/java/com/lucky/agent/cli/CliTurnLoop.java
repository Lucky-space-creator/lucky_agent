package com.lucky.agent.cli;

import com.lucky.agent.cli.approval.ApprovalHandler;
import com.lucky.agent.cli.channel.OutputSink;
import com.lucky.agent.cli.channel.TurnCapture;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次「用户输入 → 可能的多次内核往返」的完整驱动。
 *
 * <p>抽出来的原因：交互式 REPL 与 headless 一次性执行，除了「怎么问用户」不同，其余流程
 * （提交 → 等一轮 → 若内核要授权则裁决并续跑 → 若内核要做条件选择则选一个并续跑 → 收尾）
 * 完全一致。这部分逻辑一旦各写一份，两边就会慢慢分叉 —— 而分叉的后果是脚本环境与交互环境
 * 对同一条提示词的行为不一致，属最难排查的一类问题。</p>
 *
 * <p><b>三条防跑飞约束</b>（都是真实踩过的形态）：</p>
 * <ol>
 *   <li><b>续跑总步数上限</b>：授权续跑、条件选择续跑合计不超过 {@value #MAX_STEPS} 步。
 *       内核若因某轮异常反复回到 ASK，没有上限就会变成无人值守的无限循环。</li>
 *   <li><b>同操作去重</b>：同一个 {@code op} 被第二次询问即判定「内核没记住这次放行」并停止，
 *       同时打印诊断。这比无限重试诚实 —— 用户会看到「内核放行键不匹配」而不是终端莫名卡死。</li>
 *   <li><b>条件选择只认一次</b>：选择后续跑一轮，若又抛出新的选项集则不再自动选，
 *       交回用户/调用方决定（否则会变成「系统自问自答」）。</li>
 * </ol>
 */
public final class CliTurnLoop {

    /** 单次用户输入允许的最大内核往返步数（授权 + 选项续跑合计）。 */
    public static final int MAX_STEPS = 6;

    /** 条件选择的最大处理轮数（避免自动选择自己接自己的话）。 */
    private static final int MAX_CHOICE_ROUNDS = 2;

    /** 条件选择：由调用方决定如何让用户选。 */
    public interface OptionChooser {
        /**
         * @return 用户选择（选项 label 或自定义文本）；{@code null} 表示放弃选择
         */
        String choose(String question, List<Map<String, Object>> options);
    }

    /** 每步收尾时的状态上报（交互式打印状态栏；headless 可空实现）。 */
    public interface StatusReporter {
        void report(TurnOutcome outcome);
    }

    /**
     * @param outcome       最后一次内核往返的产出
     * @param askDenied     是否因用户拒绝授权而结束
     * @param steps         实际内核往返步数
     * @param choiceAbandoned 是否因用户未做条件选择而中止
     */
    public record Result(TurnOutcome outcome, boolean askDenied, int steps, boolean choiceAbandoned) {
    }

    private final CliTurnExecutor executor;
    private final ApprovalHandler approval;
    private final OutputSink out;
    private final StatusReporter reporter;

    public CliTurnLoop(CliTurnExecutor executor, ApprovalHandler approval, OutputSink out,
                       StatusReporter reporter) {
        this.executor = executor;
        this.approval = approval;
        this.out = out;
        this.reporter = reporter;
    }

    /**
     * 跑一次完整的用户请求。
     *
     * @param ref       会话
     * @param content   用户输入（已展开 {@code @} 引用）
     * @param baseExtra 基础附加参数（模型、预算覆盖等）
     * @param chooser   条件选择处理器；{@code null} 表示不支持（headless 默认）
     */
    public Result run(SessionRef ref, String content, Map<String, Object> baseExtra, OptionChooser chooser) {
        Map<String, Object> extra = baseExtra == null ? Map.of() : baseExtra;
        String current = content;
        TurnOutcome outcome = null;
        boolean askDenied = false;
        boolean choiceAbandoned = false;
        int steps = 0;
        int choiceRounds = 0;
        Set<String> askedOps = new HashSet<>();

        while (true) {
            outcome = executor.execute(ref, current, extra);
            steps++;
            report(outcome);
            if (steps > MAX_STEPS) {
                out.println(out.theme().yellow("已达单次请求的最大往返步数（" + MAX_STEPS + "），停止继续。"));
                break;
            }

            // ① 内核请求授权
            if (isAsk(outcome)) {
                Map<String, Object> op = outcome.capture().pendingAskOp();
                if (op == null) {
                    out.println(out.theme().yellow(
                            "内核请求授权但未给出操作详情，已停止（无法确定在授权什么）。"));
                    break;
                }
                if (!askedOps.add(opKey(op))) {
                    out.println(out.theme().yellow(
                            "同一操作被重复询问，已停止重试以免无限循环。\n"
                                    + "  ── 这通常意味着内核没有记住上一次放行（放行键不匹配），"
                                    + "属于内核侧问题，请据此排查而不是反复授权。"));
                    break;
                }
                ApprovalHandler.Verdict verdict = approval.ask(op, outcome.capture().askQuestion(),
                        outcome.capture().askRisk(), ref.workspaceId());
                if (!verdict.allowed()) {
                    askDenied = true;
                    break;
                }
                Map<String, Object> next = new LinkedHashMap<>(extra);
                next.put("confirm", verdict.confirm());
                extra = next;
                continue; // 内容不变，带着授权续跑
            }

            // ② 内核请求条件选择
            if (chooser != null && choiceRounds < MAX_CHOICE_ROUNDS && !outcome.capture().options().isEmpty()) {
                choiceRounds++;
                List<Map<String, Object>> options = outcome.capture().options();
                String pick = chooser.choose(optionsQuestion(outcome), options);
                if (pick == null || pick.isBlank()) {
                    choiceAbandoned = true;
                    out.println(out.theme().dim("未做选择。你可以稍后直接输入你的选择继续。"));
                    break;
                }
                current = pick;
                extra = baseExtra == null ? Map.of() : baseExtra;
                continue; // 以选项作为新的用户输入续跑（内核会在提交时消费挂起态）
            }

            break;
        }
        return new Result(outcome, askDenied, steps, choiceAbandoned);
    }

    private void report(TurnOutcome outcome) {
        if (reporter != null) {
            reporter.report(outcome);
        }
    }

    /** 是否为「等待用户授权」的收尾。 */
    private static boolean isAsk(TurnOutcome outcome) {
        TurnCapture c = outcome.capture();
        return c != null && "ask".equals(c.stopReason()) && c.pendingAskOp() != null;
    }

    private static String optionsQuestion(TurnOutcome outcome) {
        for (AgentEvent e : outcome.capture().snapshot()) {
            if ("options".equals(e.type())) {
                Object q = e.payload().get(AgentEvent.KEY_QUESTION);
                return q == null ? "" : String.valueOf(q);
            }
        }
        return "";
    }

    /** 操作指纹（用于判定「同一个操作被问了两次」）。 */
    private static String opKey(Map<String, Object> op) {
        return op.get("opType") + "\u0000" + op.get("path") + "\u0000" + op.get("args");
    }
}
