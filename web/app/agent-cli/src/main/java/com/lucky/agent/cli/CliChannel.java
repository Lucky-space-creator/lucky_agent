package com.lucky.agent.cli;

import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.SessionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import lombok.extern.slf4j.Slf4j;

/**
 * CLI 通道：消费内核 {@code Flux<AgentEvent>}，终端渲染，不持有业务逻辑（契约 §5）。
 * <p>与 WebChannel 唯一差异是渲染介质——两者订阅同一事件流、复用同一会话状态源。
 * ASK 事件在终端以交互确认形式呈现（y/n）。</p>
 */

@Slf4j
public class CliChannel {

    
    private final Scanner scanner = new Scanner(System.in);

    /** 订阅并终端渲染事件流，返回运行结束信号。*/
    public Mono<Void> consume(Flux<AgentEvent> events, SessionRef ref) {
        return events
                .publishOn(Schedulers.parallel())
                .doOnNext(this::render)
                .then();
    }

    @SuppressWarnings("unchecked")
    private void render(AgentEvent e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        switch (e.type()) {
            case "thought" -> System.out.println("  💭 " + str(p.get(AgentEvent.KEY_CONTENT)));
            case "content_delta" -> {
                // 正文流式增量：直接输出不换行，形成打字机效果
                System.out.print(str(p.get("delta")));
                System.out.flush();
            }
            case "action" -> System.out.println("  🛠 调用工具: " + str(p.get(AgentEvent.KEY_TOOL)));
            case "tool_result" -> {
                boolean ok = Boolean.TRUE.equals(p.get(AgentEvent.KEY_OK));
                String summary = str(p.get(AgentEvent.KEY_SUMMARY));
                System.out.println("  ✅ 工具结果" + (ok ? "" : "（失败）") + ": " + summary);
            }
            case "task_plan" -> {
                Object tasks = p.get(AgentEvent.KEY_TASKS);
                if (tasks instanceof List<?> list) {
                    System.out.println("  📋 任务计划：" + list.size() + " 个");
                    int i = 1;
                    for (Object t : list) {
                        if (t instanceof Map<?, ?> m) {
                            System.out.println("     " + (i++) + ". " + str(m.get(AgentEvent.KEY_TITLE)));
                        }
                    }
                }
            }
            case "task_progress" -> {
                String status = str(p.get(AgentEvent.KEY_STATUS));
                Object done = p.get(AgentEvent.KEY_DONE);
                Object total = p.get(AgentEvent.KEY_TOTAL);
                System.out.println("  🔄 进度 [" + status + "] " + done + "/" + total);
            }
            case "ask" -> handleAsk(p);
            case "token" -> System.out.println("  🔢 token: " + p.get(AgentEvent.KEY_USED)
                    + " / " + p.get(AgentEvent.KEY_TOTAL) + " (" + str(p.get(AgentEvent.KEY_MODEL)) + ")");
            case "error" -> System.out.println("  ❌ 错误: " + str(p.get(AgentEvent.KEY_MSG))
                    + " 回退=" + str(p.get(AgentEvent.KEY_FALLBACK)));
            case "stop" -> System.out.println("  🏁 结束: " + str(p.get(AgentEvent.KEY_REASON)));
            default -> { /* 其他事件静默忽略，不打断终端 */ }
        }
    }

    /** ASK 事件：终端交互确认（高危操作需用户授权，与 Web 语义一致）。*/
    private void handleAsk(Map<String, Object> p) {
        System.out.println("  ⚠️ " + str(p.get(AgentEvent.KEY_QUESTION)));
        Object op = p.get("op");
        if (op instanceof Map<?, ?> opMap) {
            System.out.println("     操作: " + str(opMap.get("opType")) + " 路径: " + str(opMap.get("path")));
        }
        System.out.print("     是否继续？(y/N): ");
        String line = scanner.nextLine().trim();
        // 确认结果由 CliRunner 在上层处理；此处仅展示，确认通过 confirm 字段回传
        pendingConfirm = "y".equalsIgnoreCase(line) || "yes".equalsIgnoreCase(line);
    }

    /** 最近一次 ASK 的用户确认结果（true=允许）。*/
    private volatile boolean pendingConfirm = false;

    public boolean takePendingConfirm() {
        boolean v = pendingConfirm;
        pendingConfirm = false;
        return v;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
