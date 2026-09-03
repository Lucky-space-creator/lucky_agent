package com.lucky.agent.core.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.constant.Phase;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Engine;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.models.dto.EngineRunResult;
import com.lucky.agent.core.runtime.AgentEventPublisher;
import com.lucky.agent.core.runtime.ConversationStateManager;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主观判定验证器（客观信号缺失时的兜底，流程图 I 节点）。
 *
 * <p>职责：把「本轮执行日志 + 客观验证证据」交给模型，要求其输出结构化结论：</p>
 * <pre>
 * {"done": true|false, "missing": ["未完成项…"], "summary": "总结"}
 * </pre>
 *
 * <p>与旧的「中文关键词黑名单」判定相比，本类做了两点强化：</p>
 * <ol>
 *   <li><b>结构化优先</b>：模型按 JSON 契约输出，判定依据是 {@code done} 字段而非正文里出现哪些词；</li>
 *   <li><b>证据注入</b>：客观验证结果一并喂给模型，主观判定不再凭空产生。</li>
 * </ol>
 *
 * <p>JSON 解析失败时降级为关键词判定，保证链路不因格式问题中断。</p>
 */
@Slf4j
public class LlmJudgeVerifier implements ObjectiveVerifier {

    /** 判定输出 JSON 契约（供提示词约束模型输出）。 */
    public static final String JUDGE_SCHEMA = """
            {"done": true, "missing": ["未完成项"], "summary": "总结"}""";

    private static final List<String> UNDONE_KEYWORDS = List.of("未完成", "仍需", "缺失", "待办", "尚未", "还没");

    private final Engine engine;
    private final ConversationStateManager stateManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmJudgeVerifier(Engine engine, ConversationStateManager stateManager) {
        this.engine = engine;
        this.stateManager = stateManager;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public boolean supports(Plan.PlanStep step, ConversationCtx ctx) {
        // 主观判定面向整体目标而非单个步骤，由 VerificationChain 以 step=null 显式调用
        return step == null;
    }

    @Override
    public VerificationResult verify(Plan.PlanStep step, ConversationCtx ctx, String execLog) {
        throw new UnsupportedOperationException("整体达成度判定请调用 judge(...)");
    }

    /**
     * 整体达成度判定（客观证据注入 + 结构化输出）。
     *
     * @param ctx       会话上下文
     * @param goal      原始目标
     * @param execLog   本轮各子任务执行结果
     * @param evidence  客观验证证据（可能为空）
     * @param publisher 事件发布器
     * @return 聚合结论
     */
    public VerificationVerdict judge(ConversationCtx ctx, String goal, String execLog,
                                     List<VerificationResult> evidence, AgentEventPublisher publisher) {
        String sessionId = ctx.sessionId();
        publisher.publish(sessionId, AgentEvent.thought(sessionId,
                "【验证】核对整体目标是否全部达成，发现遗漏将补齐…"));

        StringBuilder evidenceText = new StringBuilder();
        for (VerificationResult e : evidence) {
            evidenceText.append("\n- [").append(e.verifier()).append("] ")
                    .append(e.skipped() ? "跳过" : (e.passed() ? "通过" : "未通过"))
                    .append("：").append(e.detail());
        }
        String objectiveFailures = evidence.stream()
                .filter(e -> e.objective() && !e.skipped() && !e.passed())
                .map(VerificationResult::detail)
                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);

        String judgeGoal = "回顾如下原始目标、各子任务执行结果与客观验证证据，判断整体任务是否已全部完成。\n"
                + "若已全部完成，请直接输出简明总结（概括完成的操作、修改/新增的文件路径与最终结论）；\n"
                + "若未完全完成或存在遗漏，请先列出未完成项、继续执行补齐，最后再给出总结。\n"
                + "最终必须另起一行输出且仅输出一段 JSON 结论，格式：" + JUDGE_SCHEMA + "\n"
                + "【原始目标】" + goal
                + "\n【各子任务执行结果】" + execLog
                + (evidenceText.length() == 0 ? "" : "\n【客观验证证据】" + evidenceText)
                + (objectiveFailures.isBlank() ? "" : "\n【客观验证未通过项（必须修复）】" + objectiveFailures);

        ConversationStateManager.SessionState state = stateManager.session(ctx.sessionRef());
        state.appendMessage(UserMessage.from(judgeGoal));
        EngineRunResult r = engine.run(withSuppress(ctx, judgeGoal), Phase.ACT, judgeGoal).block();
        String text = r == null || r.finalText() == null ? "" : r.finalText();

        boolean done;
        String summary;
        String missing;
        JsonNode node = extractJsonNode(text);
        if (node != null) {
            done = node.path("done").asBoolean(false);
            StringBuilder missingSb = new StringBuilder();
            JsonNode missingNode = node.path("missing");
            if (missingNode.isArray()) {
                for (JsonNode m : missingNode) {
                    missingSb.append("\n- ").append(m.asText(""));
                }
            }
            missing = missingSb.toString();
            summary = node.path("summary").isMissingNode() || node.path("summary").asText("").isBlank()
                    ? stripJson(text) : node.path("summary").asText("");
        } else {
            // 降级：模型未输出结构化结论时回退关键词判定
            done = UNDONE_KEYWORDS.stream().noneMatch(text::contains);
            missing = done ? "" : "\n- 模型未输出结构化结论，按文本关键词判定为未达成";
            summary = stripJson(text);
            log.debug("达成度判定降级为关键词判定：session={}", sessionId);
        }

        List<VerificationResult> all = new ArrayList<>(evidence);
        all.add(VerificationResult.judged(done, done ? "模型判定目标已达成" : "模型判定仍有未完成项"));

        if (done) {
            return new VerificationVerdict(true, summary, goal, all);
        }
        String continueGoal = goal + "\n【需补齐事项】" + missing
                + (objectiveFailures.isBlank() ? "" : "\n【客观验证未通过项】\n" + objectiveFailures);
        return new VerificationVerdict(false, summary, continueGoal, all);
    }

    /** 从模型输出中截取含 done 字段的 JSON 对象；无则 null。 */
    private JsonNode extractJsonNode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed.substring(start, end + 1));
            return node.has("done") ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉结论 JSON，只保留面向用户的总结正文。 */
    private String stripJson(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        if (start <= 0) {
            return text.trim();
        }
        return text.substring(0, start).trim();
    }

    /** 编排模式上下文：suppressStop=true，判定轮不发 stop（收尾由会话层统一发布）。 */
    private ConversationCtx withSuppress(ConversationCtx base, String goal) {
        Map<String, Object> extra = new HashMap<>();
        if (base.extra() != null) {
            extra.putAll(base.extra());
        }
        extra.put("suppressStop", true);
        return ConversationCtx.builder()
                .sessionRef(base.sessionRef())
                .phase(Phase.ACT)
                .permissionLevel(base.permissionLevel())
                .goal(goal)
                .extra(extra)
                .build();
    }
}
