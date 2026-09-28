package com.lucky.agent.core.util.engine;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理模型思考链回传契约归一化（案例 K 残余修复）。
 *
 * <p>DeepSeek 等推理模型在「请求携带 tools」时，要求历史所有 assistant 消息原样携带
 * {@code reasoning_content}（即 LangChain4j 的 {@link AiMessage#thinking()}），缺失即被拒（HTTP 400
 * {@code The reasoning_content in the thinking mode must be passed back to the API.}）。</p>
 *
 * <p>当模型某轮<b>未产出</b>思考（返回空 / 无 thinking）时，该 assistant 消息与其它带 thinking 的消息构成
 * 「思考模式里混入无思考消息」的不一致，下一轮带 tools 调用即被拒。本工具在<b>发往模型前</b>归一化，
 * 不改动内存态与会话落盘（占位思考不落库）：</p>
 * <ul>
 *   <li>历史中<b>无任何</b> assistant 带 thinking → 非思考模式，原样返回（普通模型零影响）；</li>
 *   <li>处于思考模式：
 *     <ol>
 *       <li>带 tool_calls 却缺 thinking 的 assistant → 补最小非空占位（保住 tool_call/tool_result
 *         配对：丢弃会孤儿化工具结果，导致下一轮请求错乱）；</li>
 *       <li>不带 tool_calls 却缺 thinking 的 assistant（纯文本轮）→ <b>剔除</b>（案例 K 用户拍板方向：
 *         「工具轮剔除无思考消息」；纯文本不编造）；</li>
 *       <li>已带 thinking 的 assistant → 原样保留。</li>
 *     </ol>
 *   </li>
 * </ul>
 */
public final class ThinkingConsistency {

    /** 占位思考：仅用于满足推理模型回传契约，不进会话落盘、不构成语义内容。 */
    public static final String PLACEHOLDER_THINKING =
            "（本轮模型未返回思考过程，已占位以满足推理模型回传契约，不影响工具调用与上下文）";

    private ThinkingConsistency() {
    }

    public static List<ChatMessage> normalizeForThinkingMode(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        boolean thinkingMode = false;
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && !isBlank(ai.thinking())) {
                thinkingMode = true;
                break;
            }
        }
        if (!thinkingMode) {
            return messages;
        }
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && isBlank(ai.thinking())) {
                boolean hasToolCalls = ai.toolExecutionRequests() != null
                        && !ai.toolExecutionRequests().isEmpty();
                if (hasToolCalls) {
                    // 保留工具调用、补占位思考，避免孤儿化工具结果
                    out.add(ai.toBuilder().thinking(PLACEHOLDER_THINKING).build());
                }
                // 纯文本无思考 assistant → 剔除（不编造）
                continue;
            }
            out.add(m);
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
