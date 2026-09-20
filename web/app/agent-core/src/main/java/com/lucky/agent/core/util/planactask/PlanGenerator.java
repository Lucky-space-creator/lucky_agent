package com.lucky.agent.core.util.planactask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.agent.core.repository.SubAgentIntent;
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
     * 解析计划 JSON 中可选声明的 {@code subagents} 数组。
     *
     * <p>与 {@link #parse} 读取同一份模型输出，只抽取 {@code subagents} 顶层字段；
     * 无该字段或为空返回空列表。仅当 {@code core.subagent-enabled=true} 且列表非空时，
     * 主回环才会把对应子任务交给隔离子代理（TaskScheduler）执行。</p>
     *
     * @param text 模型 PLAN 输出文本
     * @return 子代理意图列表（可能为空）
     */
    public List<SubAgentIntent> parseSubagents(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String json = extractJson(text);
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode subs = node.path("subagents");
            if (!subs.isArray() || subs.isEmpty()) {
                return List.of();
            }
            List<SubAgentIntent> list = new ArrayList<>();
            for (JsonNode s : subs) {
                String id = s.path("id").asText("");
                String name = s.path("name").asText("");
                String task = s.path("task").asText("");
                if (id.isBlank() || task.isBlank()) {
                    continue;
                }
                list.add(new SubAgentIntent(
                        id,
                        name.isBlank() ? id : name,
                        task,
                        toStringList(s.get("tools")),
                        toStringList(s.get("disallowedTools")),
                        s.path("permissionMode").asText("default"),
                        s.path("summaryOnly").asBoolean(true)));
            }
            return list.isEmpty() ? List.of() : List.copyOf(list);
        } catch (Exception e) {
            log.debug("子代理声明解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) {
                out.add(v.asText());
            }
        });
        return out.isEmpty() ? null : List.copyOf(out);
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
