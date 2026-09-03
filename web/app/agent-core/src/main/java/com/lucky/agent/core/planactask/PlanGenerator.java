package com.lucky.agent.core.planactask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.core.models.Plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 计划生成：解析模型 PLAN 产出的结构化 JSON 为 {@link Plan}。
 *
 * <p>解析失败返回空，由 {@code PlanValidator} 拒绝并触发 {@code Replanner} 重规划（R3 闭环）。
 * 步骤上若声明了 {@code verify}，一并解析为客观校验声明，供主回环的客观验证器消费。</p>
 */

@Slf4j
public class PlanGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从模型输出文本解析计划。
     *
     * @param text 模型输出
     * @return 计划；解析失败返回空
     */
    public Optional<Plan> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String json = extractJson(text);
        try {
            JsonNode node = objectMapper.readTree(json);
            String goal = node.path("goal").asText("");
            boolean canAutoExecute = node.path("canAutoExecute").asBoolean(false);
            List<Plan.PlanStep> steps = new ArrayList<>();
            JsonNode stepsNode = node.path("steps");
            if (!stepsNode.isArray()) {
                return Optional.empty();
            }
            for (JsonNode step : stepsNode) {
                steps.add(new Plan.PlanStep(
                        step.path("id").asInt(steps.size() + 1),
                        step.path("type").asText("tool"),
                        step.path("desc").asText(""),
                        step.path("target").isMissingNode() ? null : step.path("target").asText(),
                        step.path("safe").asBoolean(true),
                        parseVerify(step.path("verify"),
                                step.path("target").isMissingNode() ? null : step.path("target").asText())));
            }
            if (goal.isBlank() || steps.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Plan(goal, steps, canAutoExecute));
        } catch (Exception e) {
            log.debug("计划解析失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析步骤的客观校验声明。
     *
     * <p>{@code verify} 缺失或类型非法时返回 null（该步骤不做客观验证）；
     * type=file 且未显式给 path 时回退步骤的 target。</p>
     */
    private Plan.VerifySpec parseVerify(JsonNode verifyNode, String target) {
        if (verifyNode == null || verifyNode.isMissingNode() || !verifyNode.isObject()) {
            return null;
        }
        String type = verifyNode.path("type").asText("").trim();
        String command = verifyNode.path("command").isMissingNode()
                ? null : verifyNode.path("command").asText("");
        String path = verifyNode.path("path").isMissingNode()
                ? null : verifyNode.path("path").asText("");
        String contains = verifyNode.path("contains").isMissingNode()
                ? null : verifyNode.path("contains").asText("");
        if (path == null || path.isBlank()) {
            path = target;
        }
        if (type.isBlank()) {
            return null;
        }
        Plan.VerifySpec spec = new Plan.VerifySpec(type, command, path, contains);
        return spec.actionable() ? spec : null;
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
