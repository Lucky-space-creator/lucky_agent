package com.lucky.agent.core.util.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.common.dto.AgentEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条件选择（options）解析器：从模型输出的正文中抽取结构化选项块。
 *
 * <p>约定模型在需要用户决策时，除正常说明文字外，额外输出一个受控 JSON 代码块：</p>
 * <pre>
 * ```options
 * {
 *   "question": "选择实现方案",
 *   "options": [
 *     { "id": "1", "label": "方案A：…", "detail": "…" },
 *     { "id": "2", "label": "方案B：…", "detail": "…", "recommended": true }
 *   ],
 *   "allowCustom": true,
 *   "customHint": "其他（请补充说明）"
 * }
 * ```
 * </pre>
 *
 * <p>解析成功后：<b>选项块从正文中剥离</b>（用户只看到说明文字 + 选项卡，不看到原始 JSON），
 * 由引擎发布 {@code options} 事件并挂起等待用户选择。解析失败则整体视为普通正文，不影响对话。</p>
 *
 * <p>宽松匹配：同时接受 {@code ```options}、{@code ```json options}、{@code ```json}
 * 三种围栏写法，以降低模型不稳定输出造成的能力失效。</p>
 */
@Slf4j
public final class OptionsBlockParser {

    /** 受控选项块围栏：```options / ```json options / ```json，直到下一个 ``` 结束。 */
    private static final Pattern FENCE = Pattern.compile(
            "```(?:json\\s*)?(?:options|choice|choices)\\s*\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    /** 兜底：正文里直接出现的 {\"question\"…\"options\"…} JSON 对象。 */
    private static final Pattern RAW_JSON = Pattern.compile(
            "\\{\\s*\"question\"\\s*:[\\s\\S]*?\"options\"\\s*:[\\s\\S]*?\\]\\s*\\}");

    private OptionsBlockParser() {
    }

    /**
     * 解析正文中的选项块。
     *
     * @param text 模型输出正文
     * @return 解析结果；无选项块返回 {@link Optional#empty()}
     */
    public static Optional<Parsed> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<Parsed> fenced = tryPattern(text, FENCE, true);
        if (fenced.isPresent()) {
            return fenced;
        }
        return tryPattern(text, RAW_JSON, false);
    }

    private static Optional<Parsed> tryPattern(String text, Pattern pattern, boolean stripFence) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String json = m.group(stripFence ? 1 : 0);
        try {
            Parsed parsed = toParsed(new ObjectMapper().readTree(json));
            if (parsed == null) {
                return Optional.empty();
            }
            // 剥离选项块：正文只保留说明文字，避免用户看到原始 JSON
            String body = (text.substring(0, m.start()) + text.substring(m.end())).trim();
            return Optional.of(new Parsed(parsed.question(), parsed.options(),
                    parsed.allowCustom(), parsed.customHint(), body));
        } catch (Exception e) {
            log.debug("options 块解析失败（按普通正文处理）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** JSON 节点 → Parsed；选项为空或问题为空视为无效。 */
    private static Parsed toParsed(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        String question = root.path("question").asText("").trim();
        JsonNode arr = root.path("options");
        if (question.isBlank() || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<AgentEvent.OptionItem> items = new ArrayList<>();
        int seq = 0;
        for (JsonNode node : arr) {
            seq++;
            String label = node.path("label").asText("").trim();
            if (label.isBlank()) {
                continue;
            }
            String id = node.path("id").asText("").trim();
            if (id.isBlank()) {
                id = String.valueOf(seq);
            }
            String detail = node.path("detail").asText("").trim();
            Boolean recommended = node.has("recommended") ? node.path("recommended").asBoolean() : null;
            items.add(new AgentEvent.OptionItem(id, label,
                    detail.isBlank() ? null : detail, recommended));
        }
        if (items.isEmpty()) {
            return null;
        }
        boolean allowCustom = !root.has("allowCustom") || root.path("allowCustom").asBoolean(true);
        String customHint = nodeText(root, "customHint");
        return new Parsed(question, items, allowCustom, customHint, null);
    }

    private static String nodeText(JsonNode root, String field) {
        String v = root.path(field).asText("").trim();
        return v.isBlank() ? null : v;
    }

    /**
     * 解析结果。
     *
     * @param question    决策问题
     * @param options     选项列表
     * @param allowCustom 是否允许自定义补充
     * @param customHint  自定义输入框提示
     * @param strippedBody 剥离选项块后的正文（供前端展示）
     */
    public record Parsed(String question, List<AgentEvent.OptionItem> options,
                         boolean allowCustom, String customHint, String strippedBody) {

        /** 超时未选时自动选中的选项：优先 recommended，否则第一项。 */
        public AgentEvent.OptionItem preferred() {
            return options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.recommended()))
                    .findFirst()
                    .orElse(options.get(0));
        }
    }
}
