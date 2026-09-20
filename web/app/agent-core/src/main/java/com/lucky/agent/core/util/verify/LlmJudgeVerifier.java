package com.lucky.agent.core.util.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.dto.AgentEvent;
import com.lucky.agent.common.dto.ConversationCtx;
import com.lucky.agent.core.models.Plan;
import com.lucky.agent.core.util.runtime.AgentEventPublisher;
import com.lucky.agent.model.api.ModelRouter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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
 * <p><b>P0-3 修复（轻量判定通道）</b>：判定轮不再走完整引擎，而是直接同步调用模型：</p>
 * <ul>
 *   <li>不追加合成用户消息到会话状态（上下文不再被巨型 judgeGoal 污染）；</li>
 *   <li>不以 ACT 阶段 + 完整工具集运行（判定期间不会再调用工具改文件，验证回归纯判定）；</li>
 *   <li>正文增量/JSON 不随 SSE 推给用户（不再出现「流式展示 ≠ 落库内容」的不一致）。</li>
 * </ul>
 *
 * <p>JSON 解析失败时降级为关键词判定，保证链路不因格式问题中断。</p>
 */
@Slf4j
public class LlmJudgeVerifier implements ObjectiveVerifier {

    /** 判定输出 JSON 契约（供提示词约束模型输出）。 */
    public static final String JUDGE_SCHEMA = """
            {"done": true, "missing": ["未完成项"], "summary": "总结"}""";

    /** 判定轮系统指令：纯判定、不执行（与引擎 ACT 提示词完全隔离）。 */
    private static final String JUDGE_INSTRUCTION = """
            你是任务达成度判定 Agent。仅根据给定的原始目标、执行结果与客观证据做判定，
            不要调用任何工具，不要执行任何操作，也不要修改文件。
            只输出两段内容：先给出简明总结（概括已完成的操作与最终结论），
            最后另起一行输出且仅输出一段 JSON 结论，格式：%s""".formatted(JUDGE_SCHEMA);

    private static final List<String> UNDONE_KEYWORDS = List.of("未完成", "仍需", "缺失", "待办", "尚未", "还没");

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmJudgeVerifier(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
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
        publisher.publish(sessionId, AgentEvent.progress(sessionId,
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

        String judgeGoal = "【原始目标】" + goal
                + "\n【各子任务执行结果】" + execLog
                + (evidenceText.length() == 0 ? "" : "\n【客观验证证据】" + evidenceText)
                + (objectiveFailures.isBlank() ? "" : "\n【客观验证未通过项（必须修复）】" + objectiveFailures);

        return judgeOnce(ctx, goal, judgeGoal, objectiveFailures);
    }

    /** 执行一次判定：直接同步调用模型（不经引擎、不写会话状态、不推事件流）。 */
    private VerificationVerdict judgeOnce(ConversationCtx ctx, String goal, String judgeGoal,
                                          String objectiveFailures) {
        String text = "";
        try {
            String modelId = modelIdOf(ctx);
            ChatModel model = modelRouter.resolve(modelId);
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(JUDGE_INSTRUCTION),
                            UserMessage.from(judgeGoal)))
                    .build();
            ChatResponse resp = model.chat(request);
            if (resp != null && resp.aiMessage() != null) {
                text = resp.aiMessage().text() == null ? "" : resp.aiMessage().text();
            }
        } catch (Exception e) {
            log.warn("达成度判定模型调用失败，按关键词降级：session={} err={}", ctx.sessionId(), e.getMessage());
        }

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
            log.debug("达成度判定降级为关键词判定：session={}", ctx.sessionId());
        }

        List<VerificationResult> all = new ArrayList<>();
        all.add(VerificationResult.judged(done, done ? "模型判定目标已达成" : "模型判定仍有未完成项"));

        // 判定轮不携带 evidence 之外的客观信号：客观未通过项覆盖逻辑仍由 VerificationChain 执行
        if (done) {
            return new VerificationVerdict(true, summary, goal, all);
        }
        String continueGoal = goal + "\n【需补齐事项】" + (missing == null ? "" : missing)
                + (objectiveFailures == null || objectiveFailures.isBlank() ? "" : "\n【客观验证未通过项】\n" + objectiveFailures);
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

    /** 取出用户在界面选定的模型端点 id（未指定由路由回退主端点）。 */
    private String modelIdOf(ConversationCtx ctx) {
        if (ctx == null || ctx.extra() == null) {
            return null;
        }
        Object raw = ctx.extra().get("modelId");
        return raw == null ? null : String.valueOf(raw);
    }
}