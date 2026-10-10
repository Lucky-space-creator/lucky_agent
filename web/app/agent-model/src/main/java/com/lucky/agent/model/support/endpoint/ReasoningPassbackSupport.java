package com.lucky.agent.model.support.endpoint;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理模型「思考链回传」请求侧保证（D15 修复）。
 *
 * <p>背景：DeepSeek 等 OpenAI 兼容推理模型在<b>请求携带 tools</b> 时强制要求历史 assistant
 * 消息原样携带 {@code reasoning_content}（即 {@link AiMessage#thinking()}），缺失即被拒
 * （HTTP 400 {@code The `reasoning_content` in the thinking mode must be passed back to the API.}）。
 * 但模型偶尔会产出<b>没有思考链</b>的 assistant 消息（实测 deepseek-v4-flash 对「只回复两个字：收到」
 * 这类极短回答会返回空 reasoning_content）；该消息留在会话历史里，下一次带工具调用就被厂商拒绝，
 * 且会连锁污染后续所有轮次（PLAN→重规划→ACT 全被拒）。</p>
 *
 * <p><b>与 agent-core {@code ThinkingConsistency} 的区别（本类存在的原因）</b>：后者以「请求里已有
 * 带思考的 assistant」判定思考模式——当<b>整个请求没有任何 assistant 带思考</b>时判定为「非思考模式」
 * 直接放行；而本缺陷场景恰恰是「唯一一条 assistant 没有思考」，因此漏网。本类把判定权上移到端点适配器：
 * 由端点自身能力（是否请求了推理档位 / 历史响应是否产出过思考）判定思考模式，覆盖上述情形。</p>
 *
 * <p>归一化规则（仅当调用方判定为思考模式<b>且请求带 tools</b> 时生效；只改「发往厂商」的请求副本，
 * 不动会话内存态与落盘）：</p>
 * <ul>
 *   <li>无思考但带 tool_calls 的 assistant → 补最小占位思考，保住 tool_call / tool_result 配对；</li>
 *   <li>无思考的纯文本 assistant → 剔除（不编造思考；该消息无工具配对，剔除不影响请求合法性）。</li>
 * </ul>
 *
 * <p>不带工具的请求不改动：实测该契约只对携带 tools 的请求生效，避免无谓改动上下文。</p>
 */
@Slf4j
final class ReasoningPassbackSupport {

    /** 占位思考：仅用于满足厂商回传契约，不构成语义内容，不进会话落盘。 */
    static final String PLACEHOLDER_THINKING =
            "（本轮模型未返回思考过程，已占位以满足推理模型回传契约，不影响工具调用与上下文）";

    private ReasoningPassbackSupport() {
    }

    /**
     * 按推理模型回传契约归一化请求（规则见类注释）。
     *
     * @param request      原始请求（工具名清洗后）
     * @param thinkingMode 端点是否处于思考模式
     * @return 归一化后的请求；无需调整时原样返回
     */
    static ChatRequest ensureReasoningPassback(ChatRequest request, boolean thinkingMode) {
        if (request == null || !thinkingMode) {
            return request;
        }
        if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
            return request;
        }
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return request;
        }
        boolean needsNormalize = false;
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && isBlank(ai.thinking())) {
                needsNormalize = true;
                break;
            }
        }
        if (!needsNormalize) {
            return request;
        }
        List<ChatMessage> normalized = new ArrayList<>(messages.size());
        int dropped = 0;
        int placeholder = 0;
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && isBlank(ai.thinking())) {
                if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                    normalized.add(ai.toBuilder().thinking(PLACEHOLDER_THINKING).build());
                    placeholder++;
                } else {
                    dropped++;
                }
                continue;
            }
            normalized.add(m);
        }
        log.info("[思考链回传] 请求归一化：剔除无思考纯文本 assistant {} 条、补占位 {} 条（tools={}）",
                dropped, placeholder, request.toolSpecifications().size());
        return request.toBuilder().messages(normalized).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}