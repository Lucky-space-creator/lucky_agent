package com.lucky.agent.cli.channel;

import com.lucky.agent.common.dto.AgentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单轮运行的现场采集：把本轮到达的事件按顺序收好，供轮次结束后统一取值。
 *
 * <p><b>为什么用「先收集、后扫描」而不是逐字段写入</b>：事件由内核线程（{@code boundedElastic}）
 * 投递，而轮次结束后的读取发生在主线程；若在渲染回调里逐字段赋值，字段可见性依赖框架内部的
 * 内存屏障，属于隐式假设。改为「回调只 append、结束后只读扫描」，配合
 * {@link Collections#synchronizedList(List)} 的同步语义，正确性一眼可见，不依赖时序。</p>
 *
 * <p>采集内容只用于<b>轮次结束后的收尾动作</b>（授权回传、headless 输出、状态栏、退出码判定），
 * 渲染本身是流式的、不经过本类。</p>
 */
public final class TurnCapture {

    private final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

    /** 事件到达时调用（渲染回调内）。 */
    public void add(AgentEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /** 本轮收到的全部事件快照（已复制，可安全遍历）。 */
    public List<AgentEvent> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * 最近一次 {@code ask} 事件携带的待授权操作详情。
     *
     * <p>返回内核事件里的 {@code op} 原样对象（含 {@code opType}/{@code path}/{@code args}），
     * 便于用户确认后<b>逐字段原样回传</b>。此前 CLI 回传的是自行拼接的空操作，
     * 与实际被询问的操作不对应，等于「按一次 y 放行任意后续写操作」——这里必须保真。</p>
     *
     * @return 待授权操作；无挂起或事件未携带 op 时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pendingAskOp() {
        Map<String, Object> result = null;
        for (AgentEvent e : snapshot()) {
            if (!isType(e, "ask")) {
                continue;
            }
            Object op = e.payload().get("op");
            result = (op instanceof Map<?, ?> m && !m.isEmpty()) ? (Map<String, Object>) m : null;
        }
        return result;
    }

    /** 最近一次 {@code ask} 的提问文本。 */
    public String askQuestion() {
        String result = null;
        for (AgentEvent e : snapshot()) {
            if (isType(e, "ask")) {
                result = str(e.payload().get(AgentEvent.KEY_QUESTION));
            }
        }
        return result;
    }

    /** 最近一次 {@code ask} 的风险等级（无则 null）。 */
    public String askRisk() {
        String result = null;
        for (AgentEvent e : snapshot()) {
            if (isType(e, "ask")) {
                result = str(e.payload().get(AgentEvent.KEY_RISK));
            }
        }
        return result;
    }

    /** 最近一次 {@code options} 的选项列表（无则空列表）。 */
    public List<Map<String, Object>> options() {
        List<Map<String, Object>> result = List.of();
        for (AgentEvent e : snapshot()) {
            if (!isType(e, "options")) {
                continue;
            }
            // 取「最近一次」：即使该次选项列表为空也要覆盖，避免留下上一轮的陈旧选项
            result = EventPayloads.optionList(e.payload().get(AgentEvent.KEY_OPTIONS));
        }
        return result;
    }

    /** 本轮正文（由 {@code content_delta} 增量拼接），供 headless text 输出。 */
    public String finalText() {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : snapshot()) {
            if (isType(e, "content_delta")) {
                sb.append(str(e.payload().get(AgentEvent.KEY_DELTA)));
            }
        }
        return sb.toString();
    }

    /** 最近一次 {@code stop} 的原因（无 stop 事件时为 null）。 */
    public String stopReason() {
        String result = null;
        for (AgentEvent e : snapshot()) {
            if (isType(e, "stop")) {
                result = str(e.payload().get(AgentEvent.KEY_REASON));
            }
        }
        return result;
    }

    /** 是否出现过 finish 收尾事件（stop / error 任一）。 */
    public boolean sawTerminal() {
        for (AgentEvent e : snapshot()) {
            if (isType(e, "stop") || isType(e, "error")) {
                return true;
            }
        }
        return false;
    }

    /** 本轮 token 增量合计（按 {@code token} 事件的 used 累加）。 */
    public long tokenUsed() {
        long sum = 0;
        for (AgentEvent e : snapshot()) {
            if (!isType(e, "token")) {
                continue;
            }
            Object used = e.payload().get(AgentEvent.KEY_USED);
            if (used instanceof Number n) {
                sum += n.longValue();
            }
        }
        return sum;
    }

    /** 最近一次 token 事件上报的模型名（每次调用累计的系统总量）。 */
    public String model() {
        String result = null;
        for (AgentEvent e : snapshot()) {
            if (isType(e, "token")) {
                result = str(e.payload().get(AgentEvent.KEY_MODEL));
            }
        }
        return result;
    }

    /** 最近一次 token 事件上报的会话累计总量（用于状态栏展示累计值）。 */
    public long totalTokens() {
        long result = 0;
        for (AgentEvent e : snapshot()) {
            if (!isType(e, "token")) {
                continue;
            }
            Object total = e.payload().get(AgentEvent.KEY_TOTAL);
            if (total instanceof Number n) {
                result = n.longValue();
            }
        }
        return result;
    }

    /** 本轮是否出现过错误事件。 */
    public boolean sawError() {
        for (AgentEvent e : snapshot()) {
            if (isType(e, "error")) {
                return true;
            }
        }
        return false;
    }

    /** 首条错误事件的消息文本（无则 null）。 */
    public String errorMessage() {
        for (AgentEvent e : snapshot()) {
            if (isType(e, "error")) {
                return str(e.payload().get(AgentEvent.KEY_MSG));
            }
        }
        return null;
    }

    /** 会话标题（stop 事件可选携带，供状态栏同步）。 */
    public String sessionTitle() {
        String result = null;
        for (AgentEvent e : snapshot()) {
            if (isType(e, "stop")) {
                String t = str(e.payload().get(AgentEvent.KEY_SESSION_TITLE));
                result = t == null || t.isBlank() ? result : t;
            }
        }
        return result;
    }

    private static boolean isType(AgentEvent e, String jsonValue) {
        return e != null && jsonValue.equals(e.type());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
