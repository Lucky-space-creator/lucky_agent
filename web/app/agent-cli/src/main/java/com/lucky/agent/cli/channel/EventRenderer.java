package com.lucky.agent.cli.channel;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.AgentEventType;

import java.util.List;
import java.util.Map;

/**
 * 事件渲染器：把内核 {@link AgentEvent} 渲染到终端。
 *
 * <p><b>覆盖契约全部事件类型</b>：分支按 {@link AgentEventType} 枚举穷举（经
 * {@link AgentEventType#fromJsonValue(String)} 反查，不写字面量），编译期即可发现漏项；
 * 未知类型统一走 {@link #renderUnknown}，<b>绝不静默丢弃</b>——静默丢弃曾导致
 * {@code options}（多选一）被吞、内核挂起等待而终端毫无提示、会话直接卡死。</p>
 *
 * <p><b>不刷屏原则</b>：{@code token} 逐次打印会淹没正文，故默认只在告警时输出，累计值由状态栏承载；
 * {@code content_delta} 是唯一行内流式输出（打字机效果），其余事件均整行输出。</p>
 */
public final class EventRenderer implements EventSink {

    /** 推理链折叠时保留的字符数（默认不透出完整思考，避免刷屏）。 */
    private static final int THOUGHT_PREVIEW_CHARS = 100;

    /** 工具结果 data 折叠阈值（行数）。 */
    private static final int DATA_PREVIEW_LINES = 6;

    private final OutputSink out;
    private final boolean showThinking;

    /** 上一次输出是否为未换行的流式正文，下一条整行输出前需补换行。 */
    private boolean midLine;

    public EventRenderer(OutputSink out, boolean showThinking) {
        this.out = out;
        this.showThinking = showThinking;
    }

    /** 渲染一条事件。 */
    @Override
    public void render(AgentEvent event) {
        if (event == null) {
            return;
        }
        AgentEventType type = AgentEventType.fromJsonValue(event.type());
        if (type == null) {
            renderUnknown(event);
            return;
        }
        Map<String, Object> p = event.payload() == null ? Map.of() : event.payload();
        switch (type) {
            case CONTENT_DELTA -> renderContentDelta(p);
            case THOUGHT -> renderThought(p);
            case PROGRESS -> renderProgress(p);
            case ACTION -> renderAction(p);
            case TOOL_RESULT -> renderToolResult(p);
            case SKILL_INVOKE -> renderSkill(p);
            case MCP_INVOKE -> renderMcp(p);
            case TASK_PLAN -> renderTaskPlan(p);
            case TASK_PROGRESS -> renderTaskProgress(p);
            case ASK -> renderAsk(p);
            case OPTIONS -> renderOptions(p);
            case ERROR -> renderError(p);
            case TOKEN -> renderToken(p);
            case STOP -> renderStop(p);
        }
    }

    /** 收尾：若正文停在未换行状态则补一个换行（避免与提示符粘连）。 */
    @Override
    public void endOfTurn() {
        if (midLine) {
            out.println();
            midLine = false;
        }
        out.flush();
    }

    private void renderContentDelta(Map<String, Object> p) {
        String delta = str(p.get(AgentEvent.KEY_DELTA));
        if (delta.isEmpty()) {
            return;
        }
        out.print(delta);
        midLine = true;
    }

    private void renderThought(Map<String, Object> p) {
        String content = str(p.get(AgentEvent.KEY_CONTENT));
        if (content.isEmpty()) {
            return;
        }
        newLineIfMid();
        if (showThinking) {
            out.println(out.theme().dim("💭 ") + out.theme().dim(content));
            return;
        }
        // 默认只透出一行摘要：推理过程对用户价值低且体量大，完整内容由 --show-thinking 展开
        out.println(out.theme().dim("💭 " + abbreviate(content, THOUGHT_PREVIEW_CHARS)));
    }

    private void renderProgress(Map<String, Object> p) {
        String content = str(p.get(AgentEvent.KEY_CONTENT));
        if (content.isEmpty()) {
            return;
        }
        newLineIfMid();
        out.println(out.theme().dim("· " + content));
    }

    private void renderAction(Map<String, Object> p) {
        newLineIfMid();
        String tool = str(p.get(AgentEvent.KEY_TOOL));
        String risk = str(p.get(AgentEvent.KEY_RISK));
        String args = compact(p.get(AgentEvent.KEY_ARGS), 160);
        String head = out.theme().cyan("🛠 " + tool);
        if (risk != null && !risk.isBlank() && !"LOW".equalsIgnoreCase(risk)) {
            head += " " + out.theme().yellow("[风险 " + risk + "]");
        }
        out.println(head + (args.isEmpty() ? "" : " " + out.theme().dim(args)));
    }

    private void renderToolResult(Map<String, Object> p) {
        newLineIfMid();
        boolean ok = Boolean.TRUE.equals(p.get(AgentEvent.KEY_OK));
        String summary = str(p.get(AgentEvent.KEY_SUMMARY));
        String mark = ok ? out.theme().green("✅") : out.theme().red("❌");
        out.println(mark + " " + (summary.isEmpty() ? "（无摘要）" : summary));

        // data 过长时折叠：完整内容在工具调用本身可见，此处只为可读性
        Object data = p.get(AgentEvent.KEY_DATA);
        if (data != null) {
            String text = String.valueOf(data);
            List<String> lines = text.lines().toList();
            if (!lines.isEmpty()) {
                int limit = Math.min(lines.size(), DATA_PREVIEW_LINES);
                for (int i = 0; i < limit; i++) {
                    out.println(out.theme().dim("   │ " + lines.get(i)));
                }
                if (lines.size() > limit) {
                    out.println(out.theme().dim("   │ …(+" + (lines.size() - limit) + " 行)"));
                }
            }
        }
        if (!ok) {
            String error = str(p.get(AgentEvent.KEY_ERROR));
            if (!error.isEmpty()) {
                out.println(out.theme().red("   └ " + error));
            }
        }
    }

    private void renderSkill(Map<String, Object> p) {
        newLineIfMid();
        out.println(out.theme().magenta("🧩 Skill: " + str(p.get(AgentEvent.KEY_SKILL_ID)))
                + out.theme().dim(" match=" + str(p.get(AgentEvent.KEY_MATCH))));
    }

    private void renderMcp(Map<String, Object> p) {
        newLineIfMid();
        out.println(out.theme().magenta("🔌 MCP: " + str(p.get(AgentEvent.KEY_SERVER_ID))
                + "/" + str(p.get(AgentEvent.KEY_TOOL)))
                + out.theme().dim(" source=" + str(p.get(AgentEvent.KEY_SOURCE))));
    }

    private void renderTaskPlan(Map<String, Object> p) {
        newLineIfMid();
        Object tasks = p.get(AgentEvent.KEY_TASKS);
        if (!(tasks instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        out.println(out.theme().bold("📋 任务计划（" + list.size() + " 项）"));
        int i = 1;
        for (Object t : list) {
            if (t instanceof Map<?, ?> m) {
                out.println("   " + (i++) + ". " + str(m.get(AgentEvent.KEY_TITLE)));
            }
        }
    }

    private void renderTaskProgress(Map<String, Object> p) {
        newLineIfMid();
        String status = str(p.get(AgentEvent.KEY_STATUS));
        String done = str(p.get(AgentEvent.KEY_DONE));
        String total = str(p.get(AgentEvent.KEY_TOTAL));
        out.println(out.theme().dim("🔄 进度 [" + status + "] " + done + "/" + total));
    }

    private void renderAsk(Map<String, Object> p) {
        newLineIfMid();
        String question = str(p.get(AgentEvent.KEY_QUESTION));
        String risk = str(p.get(AgentEvent.KEY_RISK));
        out.println(out.theme().danger(" ⚠️ 需要授权 ") + " " + question);
        Object op = p.get("op");
        if (op instanceof Map<?, ?> m && !m.isEmpty()) {
            out.println("   操作：" + out.theme().bold(str(m.get("opType")))
                    + "  路径：" + str(m.get("path")));
            String args = compact(m.get("args"), 240);
            if (!args.isEmpty()) {
                out.println("   参数：" + out.theme().dim(args));
            }
        }
        if (risk != null && !risk.isBlank()) {
            out.println("   风险：" + out.theme().yellow(risk));
        }
    }

    private void renderOptions(Map<String, Object> p) {
        newLineIfMid();
        out.println(out.theme().bold("❓ " + str(p.get(AgentEvent.KEY_QUESTION))));
        for (Map<String, Object> m : EventPayloads.optionList(p.get(AgentEvent.KEY_OPTIONS))) {
            boolean recommended = Boolean.TRUE.equals(m.get("recommended"));
            String label = str(m.get("label"));
            String detail = str(m.get("detail"));
            out.println("   " + out.theme().cyan("[" + str(m.get("id")) + "]") + " " + label
                    + (recommended ? " " + out.theme().green("（推荐）") : ""));
            if (detail != null && !detail.isEmpty()) {
                out.println("       " + out.theme().dim(detail));
            }
        }
        if (Boolean.TRUE.equals(p.get(AgentEvent.KEY_ALLOW_CUSTOM))) {
            String hint = str(p.get(AgentEvent.KEY_CUSTOM_HINT));
            out.println("   " + out.theme().dim(hint.isEmpty() ? "或直接输入你的选择" : hint));
        }
        Object timeout = p.get(AgentEvent.KEY_TIMEOUT_SEC);
        if (timeout instanceof Number n && n.longValue() > 0) {
            out.println("   " + out.theme().dim("（" + n.longValue() + "s 内未选择将按推荐项自动继续）"));
        }
    }

    private void renderError(Map<String, Object> p) {
        newLineIfMid();
        String stage = str(p.get(AgentEvent.KEY_STAGE));
        String msg = str(p.get(AgentEvent.KEY_MSG));
        String fallback = str(p.get(AgentEvent.KEY_FALLBACK));
        out.println(out.theme().red("❌ [" + stage + "] " + msg)
                + (fallback.isEmpty() ? "" : out.theme().dim(" 回退=" + fallback)));
    }

    private void renderToken(Map<String, Object> p) {
        // 正常 token 上报不打印（会淹没正文）；累计值见状态栏，仅在告警时提示
        if (!Boolean.TRUE.equals(p.get(AgentEvent.KEY_WARN))) {
            return;
        }
        newLineIfMid();
        out.println(out.theme().yellow("🔢 token 告警：" + str(p.get(AgentEvent.KEY_USED))
                + " / " + str(p.get(AgentEvent.KEY_TOTAL))
                + " (" + str(p.get(AgentEvent.KEY_MODEL)) + ")"));
    }

    private void renderStop(Map<String, Object> p) {
        newLineIfMid();
        String reason = str(p.get(AgentEvent.KEY_REASON));
        out.println(out.theme().dim("🏁 " + (reason.isEmpty() ? "结束" : reason)));
    }

    /**
     * 契约新增了本版本不认识的事件类型：原样透出，便于用户/开发者发现问题而非无声丢失。
     */
    private void renderUnknown(AgentEvent event) {
        newLineIfMid();
        out.println(out.theme().yellow("ℹ️ [未识别事件 " + event.type() + "] "
                + compact(event.payload(), 300)));
    }

    private void newLineIfMid() {
        if (midLine) {
            out.println();
            midLine = false;
        }
    }

    private String compact(Object value, int limit) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return abbreviate(text, limit);
    }

    private String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
